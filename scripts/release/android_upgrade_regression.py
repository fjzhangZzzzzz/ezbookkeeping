#!/usr/bin/env python3
"""Exercise the published Android update contract with real APK artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import tempfile
import threading
import time
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


PACKAGE = "top.fjzhang233.books"
CERTIFICATE = "6fe857a17e79298dee70334a4900df8935312bf312ef9f9ad6a23897a9f3735d"
BASELINE_VERSION = "0.1.8"
BASELINE_CODE = 10


def run(command: list[str]) -> str:
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apk_info(path: Path, aapt: Path, apksigner: Path) -> tuple[str, int, str]:
    badging = run([str(aapt), "dump", "badging", str(path)])
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not match:
        raise SystemExit(f"could not read APK metadata: {path.name}")
    signature = run([str(apksigner), "verify", "--verbose", "--print-certs", str(path)])
    certificate = re.search(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", signature, re.MULTILINE)
    if not certificate or certificate.group(1).lower() != CERTIFICATE:
        raise SystemExit(f"APK signer mismatch: {path.name}")
    return match.group(1), int(match.group(2)), match.group(3)


def install_apk(adb: str, path: Path, slot: str) -> None:
    remote_path = f"/data/local/tmp/ezbookkeeping-upgrade-{slot}.apk"
    push = subprocess.run(
        [adb, "push", str(path), remote_path],
        check=False,
        capture_output=True,
        text=True,
        timeout=300,
    )
    if push.returncode != 0:
        raise SystemExit(f"could not push {path.name} to emulator: {push.stdout.strip()} {push.stderr.strip()}".strip())

    try:
        last_output = ""
        for attempt in range(1, 4):
            ready = subprocess.run(
                [adb, "shell", "cmd", "package", "list", "packages"],
                check=False,
                capture_output=True,
                text=True,
                timeout=60,
            )
            if ready.returncode != 0:
                last_output = f"package service readiness failed: {ready.stdout.strip()} {ready.stderr.strip()}".strip()
                time.sleep(5)
                continue

            install = subprocess.run(
                [adb, "shell", "pm", "install", "-r", remote_path],
                check=False,
                capture_output=True,
                text=True,
                timeout=180,
            )
            last_output = f"{install.stdout.strip()} {install.stderr.strip()}".strip()
            if install.returncode == 0 and "Success" in last_output:
                return
            if attempt < 3:
                time.sleep(10)

        raise SystemExit(f"could not install {path.name} after 3 attempts: {last_output}")
    finally:
        subprocess.run([adb, "shell", "rm", "-f", remote_path], check=False, timeout=60)


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, _format: str, *_args: object) -> None:
        pass


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact_dir", type=Path)
    parser.add_argument("--baseline-url", required=True)
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--adb", type=Path, required=True)
    args = parser.parse_args()

    metadata = json.loads((args.artifact_dir / "update.json").read_text(encoding="utf-8"))
    candidate_name = metadata["apkAssetName"]
    candidate_path = args.artifact_dir / candidate_name
    if not candidate_path.is_file() or metadata["apkSha256"] != sha256(candidate_path):
        raise SystemExit("candidate APK does not match update.json")
    candidate_package, candidate_code, candidate_version = apk_info(candidate_path, args.aapt, args.apksigner)
    if candidate_package != PACKAGE or candidate_version != metadata["versionName"] or candidate_code != metadata["versionCode"]:
        raise SystemExit("candidate APK metadata does not match update.json")
    if candidate_code <= BASELINE_CODE:
        raise SystemExit("candidate versionCode is not newer than Android 0.1.8")

    with tempfile.TemporaryDirectory(prefix="android-upgrade-") as temporary:
        baseline_path = Path(temporary) / "android-v0.1.8.apk"
        urllib.request.urlretrieve(args.baseline_url, baseline_path)
        baseline_package, baseline_code, baseline_version = apk_info(baseline_path, args.aapt, args.apksigner)
        if (baseline_package, baseline_code, baseline_version) != (PACKAGE, BASELINE_CODE, BASELINE_VERSION):
            raise SystemExit("published Android 0.1.8 baseline metadata changed")

        root = Path(temporary) / "server"
        release_dir = root / "android" / "releases" / metadata["versionName"]
        release_dir.mkdir(parents=True)
        (root / "android" / "update.json").parent.mkdir(parents=True, exist_ok=True)
        (root / "android" / "update.json").write_text(json.dumps(metadata), encoding="utf-8")
        (release_dir / candidate_name).write_bytes(candidate_path.read_bytes())
        handler = lambda *handler_args, directory=str(root), **handler_kwargs: QuietHandler(*handler_args, directory=directory, **handler_kwargs)
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            manifest_url = f"http://127.0.0.1:{server.server_port}/android/update.json"
            with urllib.request.urlopen(manifest_url) as response:
                discovered = json.load(response)
            if discovered["apkAssetName"] != f"ezbookkeeping-android-{candidate_version}.apk":
                raise SystemExit("discovered metadata uses a non-stable APK filename")
            download_url = f"http://127.0.0.1:{server.server_port}/android/releases/{candidate_version}/{discovered['apkAssetName']}"
            with urllib.request.urlopen(download_url) as response:
                downloaded = response.read()
            if hashlib.sha256(downloaded).hexdigest() != discovered["apkSha256"]:
                raise SystemExit("downloaded candidate APK digest mismatch")

            adb = str(args.adb)
            subprocess.run([adb, "wait-for-device"], check=True, timeout=120)
            install_apk(adb, baseline_path, "baseline")
            install_apk(adb, candidate_path, "candidate")
            package_dump = run([adb, "shell", "dumpsys", "package", PACKAGE])
            if f"versionCode={candidate_code}" not in package_dump and f"versionCode: {candidate_code}" not in package_dump:
                raise SystemExit("Android emulator did not retain the upgraded version")
        finally:
            server.shutdown()
            thread.join(timeout=5)

    print(f"Android upgrade regression passed: {BASELINE_VERSION}/{BASELINE_CODE} -> {candidate_version}/{candidate_code}")


if __name__ == "__main__":
    main()
