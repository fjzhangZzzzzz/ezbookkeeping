#!/usr/bin/env python3
"""Validate the Android release artifact using the client update contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


VERSION_RE = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
SOURCE_RE = re.compile(r"[0-9a-f]{40}\Z")
CERTIFICATE = "6fe857a17e79298dee70334a4900df8935312bf312ef9f9ad6a23897a9f3735d"
PACKAGE = "top.fjzhang233.books"
APK_PREFIX = "ezbookkeeping-android-"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fail(message: str) -> None:
    raise SystemExit(f"Android update protocol validation failed: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact_dir", type=Path)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()

    artifact_dir = args.artifact_dir.resolve()
    metadata_path = artifact_dir / "update.json"
    sums_path = artifact_dir / "SHA256SUMS"
    if not metadata_path.is_file() or not sums_path.is_file():
        fail("update.json and SHA256SUMS are required")

    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"invalid update.json: {type(error).__name__}")

    version = metadata.get("versionName")
    if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
        fail("versionName is invalid")
    if args.release_tag != f"android-v{version}":
        fail("release tag and versionName differ")
    if metadata.get("packageId") != PACKAGE:
        fail("packageId is invalid")
    if metadata.get("channel") != "stable" or metadata.get("schemaVersion") != 1:
        fail("schemaVersion or channel is invalid")
    source_commit = metadata.get("sourceCommit")
    if not isinstance(source_commit, str) or source_commit != args.source_commit or not SOURCE_RE.fullmatch(source_commit):
        fail("sourceCommit is invalid")
    signing_certificate = metadata.get("signingCertificateSha256")
    if not isinstance(signing_certificate, str) or signing_certificate.lower().replace(":", "") != CERTIFICATE:
        fail("signing certificate is invalid")

    expected_apk_name = f"{APK_PREFIX}{version}.apk"
    if metadata.get("apkAssetName") != expected_apk_name:
        fail("apkAssetName does not use the stable client filename")
    apk_path = artifact_dir / expected_apk_name
    if not apk_path.is_file():
        fail(f"missing APK {expected_apk_name}")
    if metadata.get("apkSha256") != sha256(apk_path):
        fail("update.json apkSha256 does not match the APK")

    expected_files = {expected_apk_name, "update.json"}
    entries: dict[str, str] = {}
    for line in sums_path.read_text(encoding="utf-8").splitlines():
        parts = line.split("  ")
        if len(parts) != 2 or not re.fullmatch(r"[0-9a-f]{64}", parts[0]) or not parts[1]:
            fail("SHA256SUMS contains an invalid line")
        if parts[1] in entries:
            fail("SHA256SUMS contains a duplicate filename")
        entries[parts[1]] = parts[0]
    if set(entries) != expected_files:
        fail("SHA256SUMS does not contain exactly the APK and update.json")
    for name, digest in entries.items():
        if digest != sha256(artifact_dir / name):
            fail(f"SHA256SUMS does not match {name}")

    print(f"Android update protocol validated: {expected_apk_name}, {args.release_tag}")


if __name__ == "__main__":
    main()
