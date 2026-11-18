#!/usr/bin/env python3
"""Build the Android Release metadata from the APK that was actually verified."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path


PACKAGE = "top.fjzhang233.books"
CERTIFICATE = "6fe857a17e79298dee70334a4900df8935312bf312ef9f9ad6a23897a9f3735d"
APK_PREFIX = "ezbookkeeping-android-"
VERSION_RE = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
COMMIT_RE = re.compile(r"[0-9a-f]{40}\Z")


def run(command: list[str]) -> str:
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fail(message: str) -> None:
    raise SystemExit(f"Android release preparation failed: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--previous-version-code", type=int, required=True)
    args = parser.parse_args()

    if not args.apk.is_file():
        fail(f"APK does not exist: {args.apk.name}")
    if not args.aapt.is_file() or not args.apksigner.is_file():
        fail("Android build-tools aapt and apksigner are required")
    if not COMMIT_RE.fullmatch(args.source_commit):
        fail("source commit must be a full 40-character SHA")

    badging = run([str(args.aapt), "dump", "badging", str(args.apk)])
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    min_sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.MULTILINE)
    if not package or not min_sdk:
        fail("could not read package metadata from APK")
    package_id, version_code_text, version = package.groups()
    version_code = int(version_code_text)
    if package_id != PACKAGE:
        fail("APK package name does not match the product")
    if not VERSION_RE.fullmatch(version):
        fail("APK versionName is invalid")
    if args.release_tag != f"android-v{version}":
        fail("release tag and APK versionName differ")
    if version_code <= args.previous_version_code:
        fail("versionCode must increase from the previous formal Android release")
    if "application-debuggable" in badging:
        fail("release APK must not be debuggable")

    signature = run([str(args.apksigner), "verify", "--verbose", "--print-certs", str(args.apk)])
    certificate = re.search(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", signature, re.MULTILINE)
    if not certificate or certificate.group(1).lower() != CERTIFICATE:
        fail("APK signer does not match the dedicated release identity")

    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        fail(f"output directory is not empty: {output}")
    apk_name = f"{APK_PREFIX}{version}.apk"
    output_apk = output / apk_name
    shutil.copyfile(args.apk, output_apk)
    metadata = {
        "schemaVersion": 1,
        "channel": "stable",
        "packageId": package_id,
        "versionName": version,
        "versionCode": version_code,
        "minSdk": int(min_sdk.group(1)),
        "apkAssetName": apk_name,
        "apkSha256": sha256(output_apk),
        "signingCertificateSha256": certificate.group(1).lower(),
        "sourceCommit": args.source_commit,
    }
    (output / "update.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    with (output / "SHA256SUMS").open("w", encoding="utf-8", newline="\n") as sums:
        for name in (apk_name, "update.json"):
            sums.write(f"{sha256(output / name)}  {name}\n")

    print(f"Prepared Android release artifact: {apk_name}")


if __name__ == "__main__":
    main()
