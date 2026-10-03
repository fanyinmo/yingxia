"""Install a checksum-verified, project-local JDK and Gradle wrapper.

Does not download Android SDK packages or accept their license agreement.
"""
import hashlib
import json
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".tools"
UA = {"User-Agent": "DouyinLocalSaver-Development/0.1"}


def read(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40) as response:
        return response.read()


def download(url, path, expected):
    path.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40) as response, path.open("wb") as file:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            file.write(chunk)
    if digest.hexdigest().lower() != expected.lower():
        raise ValueError(f"Checksum mismatch: {path.name}")
    print(f"Checksum verified: {path.name}", flush=True)


def main():
    TOOLS.mkdir(exist_ok=True)
    if not list(TOOLS.glob("jdk-*/bin/java.exe")):
        assets = json.loads(read("https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"))
        package = assets[0]["binary"]["package"]
        archive = TOOLS / "downloads" / package["name"]
        print("Downloading official Eclipse Temurin JDK 17 (project-local)...", flush=True)
        download(package["link"], archive, package["checksum"])
        with zipfile.ZipFile(archive) as file:
            file.extractall(TOOLS)
    wrapper = ROOT / "gradle" / "wrapper"
    wrapper.mkdir(parents=True, exist_ok=True)
    wrapper_url = "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
    wrapper_checksum = read("https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256").decode().strip()
    download(wrapper_url, wrapper / "gradle-wrapper.jar", wrapper_checksum)
    distribution_checksum = read("https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256").decode().strip()
    (wrapper / "gradle-wrapper.properties").write_text(
        "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n"
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\n"
        f"distributionSha256Sum={distribution_checksum}\nnetworkTimeout=60000\n"
        "validateDistributionUrl=true\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n", encoding="utf-8")
    for name in ("gradlew", "gradlew.bat"):
        (ROOT / name).write_bytes(read(f"https://raw.githubusercontent.com/gradle/gradle/v8.13.0/{name}"))
    print("JDK and Gradle wrapper are ready. Android SDK still requires setup.", flush=True)


if __name__ == "__main__":
    main()

