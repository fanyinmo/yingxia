"""Install the three required SDK packages only after explicit license consent."""
import argparse
import hashlib
import os
import subprocess
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / ".tools" / "android-sdk"
TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip"
TOOLS_SHA256 = "90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--accept-license", action="store_true", help="Use only after user consent to Android SDK terms")
    args = parser.parse_args()
    if not args.accept_license:
        parser.error("Explicit user agreement to the Android SDK license is required before installation")
    assert SDK.resolve().is_relative_to(ROOT.resolve())
    sdkmanager = SDK / "cmdline-tools" / "latest" / "bin" / "sdkmanager.bat"
    if not sdkmanager.exists():
        archive = ROOT / ".tools" / "downloads" / "android-commandline-tools.zip"
        archive.parent.mkdir(parents=True, exist_ok=True)
        print("Downloading official Android command-line tools...", flush=True)
        digest = hashlib.sha256()
        with urllib.request.urlopen(TOOLS_URL, timeout=45) as response, archive.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != TOOLS_SHA256:
            raise ValueError("Android tools checksum mismatch; installation stopped")
        directory = SDK / "cmdline-tools"
        directory.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive) as package:
            package.extractall(directory)
        source = directory / "cmdline-tools"
        destination = directory / "latest"
        assert source.resolve().is_relative_to(ROOT.resolve()) and destination.resolve().is_relative_to(ROOT.resolve())
        source.rename(destination)
    java = next((ROOT / ".tools").glob("jdk-*/bin/java.exe"))
    env = dict(os.environ, JAVA_HOME=str(java.parents[1]))
    log = ROOT / "outputs" / "reports" / "android-sdk-install.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    print("Installing API 35, Build Tools 35.0.0 and Platform Tools; details in outputs/reports/android-sdk-install.log", flush=True)
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run([str(sdkmanager), f"--sdk_root={SDK}", "platforms;android-35", "build-tools;35.0.0", "platform-tools"],
            input="y\n" * 8, text=True, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"SDK setup failed ({result.returncode}); inspect {log}")
    required = [SDK / "platforms/android-35/android.jar", SDK / "build-tools/35.0.0/aapt2.exe", SDK / "platform-tools/adb.exe"]
    missing = [str(path.relative_to(SDK)) for path in required if not path.is_file() or path.stat().st_size == 0]
    if missing:
        raise RuntimeError(f"SDK setup did not produce required files: {missing}")
    (ROOT / "local.properties").write_text("sdk.dir=.tools/android-sdk\n", encoding="ascii", newline="\n")
    print("Project-local Android SDK configured.", flush=True)


if __name__ == "__main__":
    main()
