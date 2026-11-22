import json
import subprocess
import sys
import unittest
from pathlib import Path


COMMAND = [sys.executable, "-B", str(Path(__file__).with_name("check_release_retention.py"))]


def release(tag, *, draft=False, prerelease=False):
    return {
        "tag_name": tag,
        "html_url": f"https://github.com/example/books/releases/tag/{tag}",
        "draft": draft,
        "prerelease": prerelease,
    }


class ReleaseRetentionTest(unittest.TestCase):
    def test_orders_each_release_line_numerically_across_pages(self):
        pages = [
            [release("webapp-v0.1.9"), release("android-v0.1.7"), release("webapp-v0.1.10")],
            [release("webapp-v0.2.0"), release("android-v0.1.11")],
        ]
        result = subprocess.run(COMMAND, input=json.dumps(pages), capture_output=True, text=True)

        self.assertEqual(1, result.returncode)
        self.assertEqual([
            "- Keep [webapp-v0.2.0]",
            "- Keep [webapp-v0.1.10]",
            "- Review for cleanup [webapp-v0.1.9]",
            "- Keep [android-v0.1.11]",
            "- Keep [android-v0.1.7]",
        ], [line.split("(", 1)[0] for line in result.stdout.splitlines() if line.startswith("- ")])
        self.assertIn("https://github.com/example/books/releases/tag/webapp-v0.1.9", result.stdout)

    def test_ignores_drafts_prereleases_and_unrelated_tags(self):
        pages = [[
            release("webapp-v0.1.11"), release("webapp-v0.1.12"),
            release("android-v0.1.11"), release("android-v0.1.12"),
            release("webapp-v0.1.13", prerelease=True), release("android-v0.1.13", draft=True),
            release("webapp-v0.1.14-rc.1"), release("family-v1.0.0"),
        ]]
        result = subprocess.run(COMMAND, input=json.dumps(pages), capture_output=True, text=True)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(4, result.stdout.count("- Keep ["))
        self.assertNotIn("0.1.13", result.stdout)
        self.assertNotIn("rc.1", result.stdout)
        self.assertNotIn("family-v", result.stdout)

    def test_does_not_accept_missing_or_malformed_api_results(self):
        for output in ("", "[]", '{"message": "API rate limit exceeded"}', '[{"message": "Forbidden"}]'):
            with self.subTest(output=output):
                result = subprocess.run(COMMAND, input=output, capture_output=True, text=True)
                self.assertEqual(1, result.returncode)
                self.assertIn("Could not check release retention", result.stderr)
                self.assertNotIn("meet the retention limit", result.stdout)
