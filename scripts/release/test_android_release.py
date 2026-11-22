"""Exercise the Android release scripts together with synthetic build-tool output."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import android_upgrade_regression as upgrade
import prepare_android_release as prepare
import validate_android_update as validate


class AndroidReleaseTest(unittest.TestCase):
    def test_upgrade_rejects_candidate_with_the_same_code_as_explicit_baseline(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for version in ("4.5.6", "4.5.7"):
                artifact = directory / version
                artifact.mkdir()
                apk = artifact / f"ezbookkeeping-android-{version}.apk"
                apk.write_bytes(version.encode())
                (artifact / "update.json").write_text(json.dumps({
                    "packageId": validate.PACKAGE,
                    "versionCode": 52,
                    "versionName": version,
                    "apkAssetName": apk.name,
                    "apkSha256": validate.sha256(apk),
                }), encoding="utf-8")
            arguments = [
                "android_upgrade_regression.py", str(directory / "4.5.7"),
                "--baseline-apk", str(directory / "4.5.6" / "ezbookkeeping-android-4.5.6.apk"),
                "--aapt", "aapt", "--apksigner", "apksigner", "--adb", "adb",
            ]
            with patch.object(sys, "argv", arguments), patch.object(upgrade, "apk_info", side_effect=[
                (validate.PACKAGE, 52, "4.5.7"), (validate.PACKAGE, 52, "4.5.6"),
            ]), patch.object(upgrade, "install_apk") as install:
                with self.assertRaisesRegex(SystemExit, "candidate versionCode 52 must be greater than baseline versionCode 52"):
                    upgrade.main()
                install.assert_not_called()

    def test_prepared_artifacts_follow_update_protocol(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            apk = directory / "input.apk"
            apk.write_bytes(b"synthetic APK payload")
            aapt = directory / "aapt"
            aapt.touch()
            apksigner = directory / "apksigner"
            apksigner.touch()
            output = directory / "artifact"
            tag = "android-v1.2.3"
            commit = "a" * 40

            arguments = [
                "prepare_android_release.py",
                "--apk", str(apk),
                "--output-dir", str(output),
                "--aapt", str(aapt),
                "--apksigner", str(apksigner),
                "--release-tag", tag,
                "--source-commit", commit,
                "--previous-version-code", "6",
            ]
            tool_output = [
                "package: name='top.fjzhang233.books' versionCode='7' versionName='1.2.3'\nsdkVersion:'26'\n",
                "Signer #1 certificate SHA-256 digest: " + validate.CERTIFICATE + "\n",
            ]
            with patch.object(sys, "argv", arguments), patch.object(prepare, "run", side_effect=tool_output):
                prepare.main()

            with patch.object(sys, "argv", [
                "validate_android_update.py", str(output),
                "--release-tag", tag, "--source-commit", commit,
            ]):
                validate.main()
                (output / "ezbookkeeping-android-1.2.3.apk").write_bytes(b"changed payload")
                with self.assertRaisesRegex(SystemExit, "apkSha256 does not match"):
                    validate.main()
