"""Download the pinned official Gradle distribution with Python networking."""
import hashlib
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    properties = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    expected = next(line.split("=", 1)[1] for line in properties.splitlines() if line.startswith("distributionSha256Sum="))
    archive = ROOT / ".tools/downloads/gradle-8.13-bin.zip"
    archive.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    print("Downloading pinned official Gradle distribution...", flush=True)
    with urllib.request.urlopen("https://services.gradle.org/distributions/gradle-8.13-bin.zip", timeout=45) as response, archive.open("wb") as stream:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk); stream.write(chunk)
    if digest.hexdigest() != expected: raise ValueError("Gradle checksum mismatch")
    with zipfile.ZipFile(archive) as distribution: distribution.extractall(ROOT / ".tools")
    print("Official Gradle 8.13 checksum verified and extracted.", flush=True)


if __name__ == "__main__": main()
