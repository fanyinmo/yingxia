"""Publish an approved, already signed package; never reads signing keys or builds another APK.

Git blobs carry bounded transfer parts, not source-tree binaries. The release asset
is independent of these transfer objects after publication. A rerun detects the
existing matching release instead of downloading parts again.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    manifest = json.loads((ROOT / "docs/releases/publish.json").read_text(encoding="utf-8"))
    repository = os.environ["GITHUB_REPOSITORY"]
    assert repository == manifest["repository"] == "fanyinmo/yingxia"
    version = manifest["version"]
    assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    assert f'versionName = "{version}"' in gradle
    assert f'versionCode = {manifest["versionCode"]}' in gradle
    name = manifest["asset"]
    assert name == f"yingxia-{version}.apk"
    expected = manifest["sha256"]
    assert re.fullmatch(r"[a-f0-9]{64}", expected)
    parts = manifest["parts"]
    assert 1 <= len(parts) <= 300
    assert [p["index"] for p in parts] == list(range(len(parts)))
    token = os.environ["GH_TOKEN"]
    headers = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "User-Agent": "yingxia-approved-release"}

    def api(path):
        for attempt in range(3):
            try:
                request = urllib.request.Request("https://api.github.com/repos/" + repository + path, headers=headers)
                with urllib.request.urlopen(request, timeout=45) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if error.code not in (429, 500, 502, 503, 504) or attempt == 2:
                    raise
                time.sleep(2 ** attempt)

    tag = "v" + version
    try:
        release = api("/releases/tags/" + tag)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    else:
        matches = [x for x in release["assets"] if x["name"] == name and x.get("digest") == "sha256:" + expected]
        if matches and not release["draft"] and not release["prerelease"]:
            print("Matching signed package is already published: " + release["html_url"])
            return
        raise RuntimeError("This release tag already exists with a different or incomplete package; no overwrite performed")

    with tempfile.TemporaryDirectory(prefix="yingxia-approved-") as directory:
        apk = Path(directory) / name
        digest = hashlib.sha256()
        with apk.open("wb") as output:
            for part in parts:
                sha = part["blobSha"]
                assert re.fullmatch(r"[a-f0-9]{40}", sha)
                blob = api("/git/blobs/" + sha)
                assert blob["sha"] == sha and blob["encoding"] == "base64"
                data = base64.b64decode("".join(blob["content"].split()), validate=True)
                assert len(data) == part["bytes"] and len(data) <= 360 * 1024
                assert hashlib.sha256(data).hexdigest() == part["sha256"]
                output.write(data)
                digest.update(data)
        assert apk.stat().st_size == manifest["bytes"] and digest.hexdigest() == expected
        sums = Path(directory) / "SHA256SUMS.txt"
        sums.write_text(expected + "  " + name + "\n", encoding="utf-8")
        notes = ROOT / "docs/releases" / (version + ".md")
        assert notes.is_file()
        subprocess.run(["gh", "release", "create", tag, str(apk), str(sums), "--target", os.environ["GITHUB_SHA"],
                        "--title", "影匣 " + version, "--notes-file", str(notes), "--latest"], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
