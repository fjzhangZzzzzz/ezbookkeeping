#!/usr/bin/env python3
"""Report which formal GitHub Releases should be kept or reviewed."""

import json
import re
import sys


RELEASE_TAG = re.compile(r"(webapp|android)-v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
RETAINED_COUNT = 2


def report(pages):
    if not isinstance(pages, list) or not pages or any(not isinstance(page, list) for page in pages):
        raise ValueError("expected paginated GitHub Releases JSON")

    releases = {"webapp": [], "android": []}
    for page in pages:
        for release in page:
            tag = release["tag_name"]
            match = RELEASE_TAG.fullmatch(tag)
            if match and not release["draft"] and not release["prerelease"]:
                unit, *version_parts = match.groups()
                version = tuple(map(int, version_parts))
                releases[unit].append((version, tag, release["html_url"]))

    lines = ["# Release retention", ""]
    has_excess = False
    for unit, label in (("webapp", "Webapp"), ("android", "Android")):
        lines.extend([f"## {label}", ""])
        ordered = sorted(releases[unit], reverse=True)
        has_excess |= len(ordered) > RETAINED_COUNT
        for index, (_, tag, url) in enumerate(ordered):
            status = "Keep" if index < RETAINED_COUNT else "Review for cleanup"
            lines.append(f"- {status} [{tag}]({url})")
        if not ordered:
            lines.append("No formal releases.")
        lines.append("")

    if has_excess:
        lines.append("Confirm download and rollback references before removing any Release, then rerun this check.")
    else:
        lines.append("Both release lines meet the retention limit.")
    return "\n".join(lines), has_excess


def main():
    try:
        summary, has_excess = report(json.load(sys.stdin))
    except (ValueError, KeyError, TypeError) as error:
        print(f"Could not check release retention: {error}", file=sys.stderr)
        return 1
    print(summary)
    return int(has_excess)


if __name__ == "__main__":
    sys.exit(main())
