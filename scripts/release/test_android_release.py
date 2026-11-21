"""Exercise the Android release scripts together with synthetic build-tool output."""

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import prepare_android_release as prepare
import validate_android_update as validate


class AndroidReleaseTest(unittest.TestCase):
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
