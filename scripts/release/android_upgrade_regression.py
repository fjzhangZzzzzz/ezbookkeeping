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

from validate_android_update import CERTIFICATE, PACKAGE, VERSION_RE, sha256


def run(command: list[str]) -> str:
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)


def apk_info(path: Path, aapt: Path, apksigner: Path) -> tuple[str, int, str]:
    badging = run([str(aapt), "dump", "badging", str(path)])
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not match:
        raise SystemExit(f"could not read APK metadata: {path.name}")
    if "application-debuggable" in badging:
        raise SystemExit(f"APK is debuggable: {path.name}")
    signature = run([str(apksigner), "verify", "--verbose", "--print-certs", str(path)])
    certificate = re.search(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", signature, re.MULTILINE)
    if not certificate or certificate.group(1).lower() != CERTIFICATE:
        raise SystemExit(f"APK signer mismatch: {path.name}")
    return match.group(1), int(match.group(2)), match.group(3)


def verified_apk(path: Path, metadata: dict, aapt: Path, apksigner: Path) -> tuple[int, str]:
    version = metadata.get("versionName")
    if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
        raise SystemExit(f"invalid release version in update.json: {path.name}")
    if metadata.get("apkAssetName") != path.name or path.name != f"ezbookkeeping-android-{version}.apk":
        raise SystemExit(f"APK filename does not match update.json: {path.name}")
    if not path.is_file() or metadata.get("apkSha256") != sha256(path):
        raise SystemExit(f"APK digest does not match update.json: {path.name}")
    package, code, apk_version = apk_info(path, aapt, apksigner)
    if package != PACKAGE or (package, code, apk_version) != (metadata.get("packageId"), metadata.get("versionCode"), version):
        raise SystemExit(f"APK metadata does not match update.json: {path.name}")
    return code, version


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
        for attempt in range(1, 3):
            ready = subprocess.run(
                [adb, "shell", "cmd", "package", "list", "packages"],
                check=False,
                capture_output=True,
                text=True,
                timeout=60,
            )
            if ready.returncode != 0:
                last_output = f"package service readiness failed: {ready.stdout.strip()} {ready.stderr.strip()}".strip()
                time.sleep(15)
                continue

            install = subprocess.run(
                [adb, "shell", "pm", "install", "-r", remote_path],
                check=False,
                capture_output=True,
                text=True,
                timeout=450,
            )
            last_output = f"{install.stdout.strip()} {install.stderr.strip()}".strip()
            if install.returncode == 0 and "Success" in last_output:
                return
            if attempt < 2:
                time.sleep(10)

        raise SystemExit(f"could not install {path.name} after 2 attempts: {last_output}")
    finally:
        subprocess.run([adb, "shell", "rm", "-f", remote_path], check=False, timeout=60)


def require_installed_version(adb: str, code: int, version: str) -> None:
    package_dump = run([adb, "shell", "dumpsys", "package", PACKAGE])
    if not re.search(rf"(?m)^\s*versionCode[=:]\s*{code}\b", package_dump) or not re.search(
        rf"(?m)^\s*versionName={re.escape(version)}\s*$", package_dump
    ):
        raise SystemExit(f"Android emulator did not retain {version}/{code}")


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, _format: str, *_args: object) -> None:
        pass


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact_dir", type=Path)
    parser.add_argument("--baseline-apk", required=True, type=Path, help="formal APK with its update.json alongside it")
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--adb", type=Path, required=True)
    args = parser.parse_args()

    metadata = json.loads((args.artifact_dir / "update.json").read_text(encoding="utf-8"))
    candidate_name = metadata["apkAssetName"]
    candidate_path = args.artifact_dir / candidate_name
    candidate_code, candidate_version = verified_apk(candidate_path, metadata, args.aapt, args.apksigner)
    baseline_metadata = json.loads(args.baseline_apk.with_name("update.json").read_text(encoding="utf-8"))
    baseline_code, baseline_version = verified_apk(args.baseline_apk, baseline_metadata, args.aapt, args.apksigner)
    if candidate_code <= baseline_code:
        raise SystemExit(f"candidate versionCode {candidate_code} must be greater than baseline versionCode {baseline_code}")

    with tempfile.TemporaryDirectory(prefix="android-upgrade-") as temporary:
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
            install_apk(adb, args.baseline_apk, "baseline")
            require_installed_version(adb, baseline_code, baseline_version)
            install_apk(adb, candidate_path, "candidate")
            require_installed_version(adb, candidate_code, candidate_version)
        finally:
            server.shutdown()
            thread.join(timeout=5)

    print(f"Android upgrade regression passed: {baseline_version}/{baseline_code} -> {candidate_version}/{candidate_code}")


if __name__ == "__main__":
    main()
