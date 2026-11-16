"""Publish the tested Actions image; never replace a different release image."""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def android_companion_status():
    tags = command("git", "tag", "--list", "android-v*", "--sort=-version:refname").splitlines()
    if not tags:
        raise RuntimeError("no android-v* tag available for Family release coordination")
    tag = tags[0]
    source_commit = command("git", "rev-list", "-n", "1", tag)
    changed = subprocess.run(
        ["git", "diff", "--quiet", f"{tag}..HEAD", "--", "android"],
        check=False,
    ).returncode != 0
    return {
        "status": "pending" if changed else "unchanged",
        "release_tag": tag,
        "source_commit": source_commit,
    }


def registry_token(image_path, authenticated):
    # Match docker push's scope for first-package creation; anonymous checks only pull.
    actions = "pull,push" if authenticated else "pull"
    query = urllib.parse.urlencode({"service": "ghcr.io", "scope": f"repository:{image_path}:{actions}"})
    request = urllib.request.Request(f"https://ghcr.io/token?{query}")
    if authenticated:
        credential = base64.b64encode(f"{os.environ['GITHUB_ACTOR']}:{os.environ['GH_TOKEN']}".encode()).decode()
        request.add_header("Authorization", f"Basic {credential}")
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)["token"]


def manifest(image_path, reference, token, allow_missing=False):
    request = urllib.request.Request(f"https://ghcr.io/v2/{image_path}/manifests/{reference}")
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Accept", "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read()
            digest = response.headers["Docker-Content-Digest"]
            assert digest == "sha256:" + hashlib.sha256(body).hexdigest(), "Registry manifest digest mismatch"
            return digest, json.loads(body)
    except urllib.error.HTTPError as error:
        if allow_missing and error.code == 404:
            return None
        raise


def main():
    repository = os.environ["GITHUB_REPOSITORY"]
    assert repository == "fjzhangZzzzzz/ezbookkeeping", "Unexpected publishing repository"
    commit = os.environ["GITHUB_SHA"]
    tag = os.environ["RELEASE_TAG"]
    assert re.fullmatch(r"[a-f0-9]{40}", commit)
    assert re.fullmatch(r"family-v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag)
    version = tag.removeprefix("family-v")
    assert json.loads(Path("package.json").read_text())["version"] == version
    image = "ghcr.io/fjzhangzzzzzz/ezbookkeeping-family"
    image_path = image.removeprefix("ghcr.io/")
    local = json.loads(command("docker", "image", "inspect", "ezbookkeeping-family:ci"))[0]
    labels = local["Config"]["Labels"]
    assert labels["org.opencontainers.image.revision"] == commit
    assert labels["org.opencontainers.image.version"] == version
    assert labels["org.opencontainers.image.source"] == f"https://github.com/{repository}"
    assert local["Architecture"] == "amd64" and local["Os"] == "linux"
    smoke = json.loads(Path("release-artifact/smoke.json").read_text())
    assert smoke["commit"] == commit and smoke["version"] == version and smoke["passed"] is True

    token = registry_token(image_path, authenticated=True)
    references = [version, f"sha-{commit}"]
    existing = {reference: manifest(image_path, reference, token, allow_missing=True) for reference in references}
    for reference, result in existing.items():
        if result:
            assert result[1].get("config", {}).get("digest") == local["Id"], f"Refusing to overwrite different image: {reference}"
    for reference, result in existing.items():
        if not result:
            target = f"{image}:{reference}"
            subprocess.run(["docker", "tag", "ezbookkeeping-family:ci", target], check=True)
            subprocess.run(["docker", "push", target], check=True)
    published = [manifest(image_path, reference, token) for reference in references]
    digest = published[0][0]
    assert all(result[0] == digest and result[1]["config"]["digest"] == local["Id"] for result in published)

    # This request has no workflow credential: public repo does not imply public package.
    try:
        anonymous_token = registry_token(image_path, authenticated=False)
        assert manifest(image_path, digest, anonymous_token)[0] == digest
    except (urllib.error.HTTPError, AssertionError) as error:
        raise RuntimeError("GHCR package is not anonymously pullable. Make ezbookkeeping-family public in GitHub package settings, then rerun only the failed publish job to reuse this verified image.") from error

    output = Path("release-files")
    output.mkdir(exist_ok=True)
    metadata = {
        "schema": 1,
        "product": "ezbookkeeping-family",
        "version": version,
        "tag": tag,
        "source": {"repository": f"https://github.com/{repository}", "commit": commit},
        "upstream": json.loads(Path("family-release.json").read_text())["upstream"],
        "image": {"platform": "linux/amd64", "reference": f"{image}@{digest}", "digest": digest, "tags": [f"{image}:{ref}" for ref in references], "config_id": local["Id"], "anonymous_pull_verified": True},
        "build": {"method": "GitHub Actions Linux Dockerfile; backend and frontend from the same source commit", "workflow": f"https://github.com/{repository}/actions/runs/{os.environ['GITHUB_RUN_ID']}", "backend_vet_tests": True, "frontend_typecheck_lint_tests_build": True, "third_party_api_probes": False},
        "smoke": smoke,
        "companion_artifacts": {"android": android_companion_status()},
        "deployment_acceptance": "Public HTTPS, Pocket ID login and backup/restore acceptance belong to homelab and are not claimed by this release."
    }
    filename = f"ezbookkeeping-family-{version}-build-info.json"
    asset = output / filename
    asset.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "SHA256SUMS").write_text(f"{hashlib.sha256(asset.read_bytes()).hexdigest()}  {filename}\n", encoding="utf-8")
    # Existing releases are never edited or replaced by a rerun.
    result = subprocess.run(["gh", "api", f"repos/{repository}/releases/tags/{tag}"], capture_output=True, text=True)
    if result.returncode == 0:
        raise RuntimeError("Release already exists; refusing to replace it")
    assert "404" in result.stderr, "Could not verify release absence"
    subprocess.run(["gh", "release", "create", tag, str(asset), str(output / "SHA256SUMS"), "--repo", repository, "--verify-tag", "--target", commit, "--title", f"ezBookkeeping Family {version}", "--prerelease", "--notes-file", f"docs/family-review/releases/{tag}.md"], check=True)
    print(f"Published {image}@{digest}")


if __name__ == "__main__":
    main()
