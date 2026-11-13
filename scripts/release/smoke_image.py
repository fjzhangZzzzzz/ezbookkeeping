#!/usr/bin/env python3
"""Exercise a newly built family image against disposable local SQLite data.

Requires only Python's standard library and a local Docker daemon. This is an
image release check, not a production deployment or an OIDC/device acceptance
test. Synthetic credentials are generated in memory and never enter arguments
or the result artifact.
"""

import argparse
import configparser
import datetime
from html.parser import HTMLParser
import json
from pathlib import Path
import secrets
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


class SmokeFailure(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise SmokeFailure(message)


def docker(*arguments, stdin=None, check=True):
    result = subprocess.run(
        ["docker", *arguments], input=stdin, text=True, capture_output=True,
        timeout=120,
    )
    if check and result.returncode:
        # CLI/API responses can contain credentials; never echo their bodies.
        raise SmokeFailure(f"Docker {arguments[0]} failed (exit {result.returncode})")
    return result


class LocalAssets(HTMLParser):
    def __init__(self):
        super().__init__()
        self.paths = set()

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "script" and attrs.get("src"):
            self.paths.add(attrs["src"])
        if tag == "link" and attrs.get("rel") in {"stylesheet", "modulepreload"}:
            if attrs.get("href"):
                self.paths.add(attrs["href"])


class HttpClient:
    def __init__(self, origin):
        self.origin = origin
        # CI proxy settings must not send loopback credentials to a proxy.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.token = None

    def fetch(self, path, payload=None, authenticated=True):
        headers = {"X-Timezone-Offset": "480", "X-Timezone-Name": "Asia/Shanghai"}
        if authenticated and self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(self.origin + path, data=data, headers=headers)
        try:
            with self.opener.open(request, timeout=10) as response:
                require(response.status == 200, f"Unexpected HTTP status for {path}")
                return response.read()
        except urllib.error.HTTPError as error:
            # Do not expose response contents or the authentication request.
            raise SmokeFailure(f"HTTP {error.code} for {path}") from None

    def api(self, path, payload=None):
        response = json.loads(self.fetch(path, payload))
        require(response.get("success") is True, f"Application request failed: {path}")
        return response["result"]

    def wait_ready(self, commit, version):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                health = self.api("/healthz.json")
            except (OSError, ValueError, SmokeFailure):
                time.sleep(0.5)
                continue
            require(health.get("status") == "ok", "Health status is not ok")
            require(health.get("commit") == commit, "Backend source commit mismatch")
            require(health.get("version") == version, "Backend release version mismatch")
            return
        raise SmokeFailure("Server did not become ready within 60 seconds")


def make_config(image, destination):
    defaults = docker(
        "run", "--rm", "--network", "none", "--read-only", "--entrypoint", "cat",
        image, "/ezbookkeeping/conf/ezbookkeeping.ini",
    ).stdout
    config = configparser.ConfigParser(interpolation=None)
    config.read_string(defaults)
    overrides = {
        "server": {
            "http_addr": "0.0.0.0", "http_port": "8080", "protocol": "http",
            "root_url": "http://localhost:8080/", "log_request": "false",
        },
        "database": {
            "type": "sqlite3", "db_path": "/ezbookkeeping/data/smoke.db",
            "auto_update_database": "true", "log_query": "false",
        },
        "storage": {"type": "local_filesystem", "local_filesystem_path": "/ezbookkeeping/storage/"},
        "log": {"mode": "console", "level": "warn"},
        "security": {"secret_key": secrets.token_urlsafe(48), "trusted_proxy_ips": "127.0.0.1/32"},
        "auth": {"enable_internal_auth": "true", "enable_oauth2_auth": "false"},
        "user": {"enable_register": "false", "enable_custom_icon": "false", "enable_scheduled_transaction": "false"},
        "mail": {"enable_smtp": "false"},
        "uuid": {"generator_type": "internal", "server_id": "0"},
        "duplicate_checker": {"checker_type": "in_memory"},
        "cron": {"enable_create_scheduled_transaction": "false"},
        "mcp": {"enable_mcp": "false"},
        "ai": {
            "transaction_from_ai_text_recognition": "false",
            "transaction_from_ai_image_recognition": "false",
            "insights_explorer_coding_assistant": "false",
        },
        "map": {"map_provider": "", "map_data_fetch_proxy": "false",
                "amap_security_verification_method": "internal_proxy"},
        "exchange_rates": {"data_source": "user_custom"},
        "data": {"enable_import": "true", "enable_export": "true"},
    }
    for section, values in overrides.items():
        if not config.has_section(section):
            config.add_section(section)
        for key, value in values.items():
            config.set(section, key, value)
    with destination.open("w", encoding="utf-8") as stream:
        config.write(stream)
    destination.chmod(0o644)


def verify_web(client, commit, version):
    assets = set()
    for route in ("/", "/desktop", "/mobile"):
        html = client.fetch(route).decode("utf-8")
        require("<html" in html.lower(), f"Missing HTML document at {route}")
        parser = LocalAssets()
        parser.feed(html)
        require(parser.paths, f"No application assets at {route}")
        for reference in parser.paths:
            resolved = urllib.parse.urljoin(client.origin + route, reference)
            parsed = urllib.parse.urlsplit(resolved)
            require(parsed.netloc == urllib.parse.urlsplit(client.origin).netloc,
                    "Web application references an unexpected external asset")
            assets.add(parsed.path)
    scripts = []
    for path in sorted(assets):
        data = client.fetch(path)
        require(data, f"Empty static resource: {path}")
        if path.endswith(".js"):
            scripts.append(data)
    require(scripts, "Missing JavaScript application resources")
    bundled = b"\n".join(scripts)
    require(commit.encode() in bundled, "Frontend source commit mismatch")
    require(version.encode() in bundled, "Frontend release version missing")
    manifest = json.loads(client.fetch("/manifest.json"))
    require(manifest.get("display") == "standalone", "PWA manifest is missing standalone mode")
    require(client.fetch("/sw.js"), "PWA service worker is empty")
    return len(assets)


def verify_business(client):
    now = int(time.time())
    month = datetime.datetime.fromtimestamp(
        now, datetime.timezone(datetime.timedelta(hours=8))
    ).strftime("%Y-%m")
    account = client.api("/api/v1/accounts/add.json", {
        "name": "Release smoke CNY", "category": 1, "type": 1,
        "icon": "1", "iconType": 0, "color": "336699", "currency": "CNY",
        "balance": "0", "balanceTime": now, "comment": "Disposable release check",
        "clientSessionId": "account-" + secrets.token_hex(16),
    })
    categories = client.api("/api/v1/transaction/categories/list.json?type=2")
    leaves = [
        child for parent in categories.get("2", [])
        for child in parent.get("subCategories", [])
        if not child.get("familyInternalTransfer") and not child.get("hidden")
    ]
    require(leaves, "No ordinary expense category in the shared directory")
    payload = {
        "type": 3, "categoryId": leaves[0]["id"], "time": now, "utcOffset": 480,
        "sourceAccountId": account["id"], "sourceAmount": 123,
        "destinationAccountId": "0", "destinationAmount": 0,
        "tagIds": [], "pictureIds": [], "comment": "Release smoke expense",
        "clientSessionId": "transaction-" + secrets.token_hex(16),
    }
    transaction = client.api("/api/v1/transactions/add.json", payload)
    replay = client.api("/api/v1/transactions/add.json", payload)
    require(replay["id"] == transaction["id"], "Transaction retry created a second entry")
    verify_saved(client, account["id"], transaction["id"], month)
    return account["id"], transaction["id"], month, payload


def verify_saved(client, account_id, transaction_id, month):
    account = client.api("/api/v1/accounts/get.json?id=" + account_id)
    require(account["balance"] == "-123", "Persisted account balance is incorrect")
    overview = client.api("/api/v1/family/overview.json?month=" + month)
    require(overview["timezone"] == "Asia/Shanghai", "Family timezone is incorrect")
    require(overview["currency"] == "CNY", "Family currency is incorrect")
    require(overview["totals"] == {
        "incomeMinor": "0", "expenseMinor": "123", "netMinor": "-123",
    }, "Family totals do not match the saved transaction")
    page = client.api("/api/v1/family/transactions/list.json?month=" + month)
    require(sum(item["id"] == transaction_id for item in page["items"]) == 1,
            "Family detail is missing or duplicates the saved transaction")


def smoke(image, commit, version):
    image_info = json.loads(docker("image", "inspect", image).stdout)[0]
    require(image_info["Os"] == "linux" and image_info["Architecture"] == "amd64",
            "Release image must be linux/amd64")
    require(image_info["Config"].get("User") == "1000:1000", "Image user is not UID/GID 1000")
    name = "ezbookkeeping-release-smoke-" + secrets.token_hex(8)
    with tempfile.TemporaryDirectory(prefix="ezbookkeeping-release-smoke-") as temp:
        directory = Path(temp)
        configuration = directory / "smoke.ini"
        make_config(image, configuration)
        mounts = ["--mount", f"type=bind,src={configuration},dst=/smoke.ini,readonly"]
        for folder in ("data", "storage", "log"):
            path = directory / folder
            path.mkdir(mode=0o777)
            path.chmod(0o777)  # Disposable synthetic data only; permits container UID 1000.
            mounts.extend(["--mount", f"type=bind,src={path},dst=/ezbookkeeping/{folder}"])
        options = ["--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                   "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=32m", *mounts]
        executable = ["/ezbookkeeping/ezbookkeeping", "--conf-path=/smoke.ini"]
        docker("run", "--rm", "--network", "none", *options, image, *executable, "database", "update")
        password = secrets.token_urlsafe(32)
        docker("run", "--rm", "-i", "--network", "none", *options, image, *executable,
               "userdata", "user-add", "--username", "releasesmoke", "--nickname", "Release smoke",
               "--email", "release-smoke@example.com", "--default-currency", "CNY", "--password-stdin",
               stdin=password + "\n")
        try:
            docker("run", "-d", "--name", name, "-p", "127.0.0.1::8080", *options,
                   image, *executable, "server", "run")
            port = docker("port", name, "8080/tcp").stdout.strip()
            require(port.startswith("127.0.0.1:"), "Smoke server was not bound to loopback")
            client = HttpClient("http://" + port)
            client.wait_ready(commit, version)
            container = json.loads(docker("inspect", name).stdout)[0]
            require(container["HostConfig"]["ReadonlyRootfs"], "Container root filesystem is writable")
            require(docker("exec", name, "touch", "/ezbookkeeping/readonly-probe", check=False).returncode != 0,
                    "Container root filesystem write unexpectedly succeeded")
            assets = verify_web(client, commit, version)
            auth = client.api("/api/authorize.json", {"loginName": "releasesmoke", "password": password})
            require(auth.get("token") and not auth.get("need2FA"), "Password login did not establish a session")
            client.token = auth["token"]
            account_id, transaction_id, month, request = verify_business(client)
            docker("restart", name)
            # Docker may allocate a different ephemeral published port after restart.
            restarted_port = docker("port", name, "8080/tcp").stdout.strip()
            require(restarted_port.startswith("127.0.0.1:"), "Restarted server escaped loopback")
            client.origin = "http://" + restarted_port
            client.wait_ready(commit, version)
            verify_saved(client, account_id, transaction_id, month)
            replay = client.api("/api/v1/transactions/add.json", request)
            require(replay["id"] == transaction_id, "Restart lost the transaction request record")
            verify_saved(client, account_id, transaction_id, month)
            docker("stop", name)
            with sqlite3.connect(f"file:{directory / 'data' / 'smoke.db'}?mode=ro", uri=True) as database:
                require(database.execute("PRAGMA integrity_check").fetchall() == [("ok",)],
                        "SQLite integrity check failed")
            return {
                "passed": True, "commit": commit, "version": version,
                "image_id": image_info["Id"], "platform": "linux/amd64",
                "checks": {
                    "non_root_readonly": True, "backend_version_commit": True,
                    "web_version_commit": True, "web_assets_checked": assets, "pwa_resources": True,
                    "sqlite_user_cli_stdin": True, "password_login": True, "transaction_balance_family": True,
                    "idempotent_retry": True, "restart_session_data_retry": True, "sqlite_integrity": True,
                },
                "not_covered": ["production deployment", "real OIDC login", "physical phone", "backup restore"],
            }
        finally:
            docker("rm", "-f", name, check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    require(len(arguments.commit) == 40 and all(character in "0123456789abcdef" for character in arguments.commit),
            "A full lowercase source commit SHA is required")
    result = smoke(arguments.image, arguments.commit, arguments.version)
    serialized = json.dumps(result, indent=2) + "\n"
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(serialized, encoding="utf-8")
    print(serialized, end="")


if __name__ == "__main__":
    try:
        main()
    except (SmokeFailure, OSError, ValueError, subprocess.SubprocessError, sqlite3.Error) as error:
        print(f"Image smoke failed: {error}", file=sys.stderr)
        sys.exit(1)
