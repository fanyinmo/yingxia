"""Generate/check independent, frame-matched LIVE test inputs with an existing FFmpeg.

Only locked original MP4s are read. Covers are decoded from their actual frame indices,
including their 160-pixel side bars; they are never output from Android export code.
Existing motion fixtures/manifests are not rewritten. No tools are installed/downloaded.
Usage: python scripts/generate_live_cover_fixtures.py --ffmpeg /path/to/ffmpeg [--check]
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from generate_fixed_motion_fixture import boxes, child, clock_header, track_manifest


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/androidTest/assets/motion_test"
MANIFEST_NAME = "fixed_live_covers.json"
SOURCES = {
    "fixed_red_blue_2s.mp4": "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57",
    "fixed_blue_red_4s.mp4": "6bffa756909f402a6445a8c3f65e2921b2f645acdaa739e3f54ee0f32c88b7a4",
}
SPECS = [
    ("fixed_red_blue_2s_cover_red_000.jpg", "fixed_red_blue_2s.mp4", 0, "red"),
    ("fixed_red_blue_2s_cover_red_000.png", "fixed_red_blue_2s.mp4", 0, "red"),
    ("fixed_red_blue_2s_cover_blue_045.png", "fixed_red_blue_2s.mp4", 45, "blue"),
    ("fixed_blue_red_4s_cover_blue_000.png", "fixed_blue_red_4s.mp4", 0, "blue"),
]
WIDTH, HEIGHT = 1280, 720
BASE_ARGS = ["-hide_banner", "-loglevel", "error", "-nostdin"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(ffmpeg, arguments):
    result = subprocess.run([str(ffmpeg), *map(str, arguments)], capture_output=True, timeout=90)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace"))
    return result.stdout


def original_video(source_name):
    source = TARGET / source_name
    data = source.read_bytes()
    require(digest(data) == SOURCES[source_name], f"Original MP4 changed: {source_name}")
    original = json.loads(source.with_suffix(".json").read_text(encoding="utf-8"))
    require(original["validation"]["sha256"] == SOURCES[source_name], "Original source manifest hash differs")
    moov = child(data, "moov")
    scale, _ = clock_header(child(moov, "mvhd"))
    tracks = [track_manifest(payload, scale) for name, payload in boxes(moov) if name == "trak"]
    video = next(track for track in tracks if track["handler"] == "vide")
    require(video == next(track for track in original["validation"]["tracks"] if track["handler"] == "vide"),
            "Actual MP4 video timing/geometry differs from its original independent manifest")
    require((video["width"], video["height"]) == (WIDTH, HEIGHT), "Original geometry changed")
    require(video["sampleAspectRatio"] == [1, 1] and video["rotationDegrees"] == 0, "Original display transform changed")
    return source, video


def frame_arguments(source, frame_index):
    # Select the decoder's actual frame index. No nearest-keyframe seek or frame synthesis.
    return BASE_ARGS + ["-i", source, "-map", "0:v:0", "-an", "-vf", f"select=eq(n\\,{frame_index})",
                        "-frames:v", "1", "-fps_mode", "passthrough", "-map_metadata", "-1", "-threads:v", "1"]


def rgb_frame(ffmpeg, arguments):
    raw = run(ffmpeg, arguments + ["-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"])
    require(len(raw) == WIDTH * HEIGHT * 3, "Decoder did not produce exactly one full-size source frame")
    return raw


def pixels(raw, color):
    def at(x, y):
        offset = (y * WIDTH + x) * 3
        return list(raw[offset:offset + 3])
    samples = {"center": at(WIDTH // 2, HEIGHT // 2)}
    center = samples["center"]
    require((center[0] > center[2] + 80) if color == "red" else (center[2] > center[0] + 80),
            "Frame center differs from the intended source frame's color")
    # Inspect all rows of the exterior bars, not merely a single black pixel.
    for side, start, end in (("left", 0, 156), ("right", 1124, WIDTH)):
        maximum = max(channel for y in range(HEIGHT) for channel in raw[(y * WIDTH + start) * 3:(y * WIDTH + end) * 3])
        require(maximum <= 4, f"Full {side} black side bar was not retained")
        samples[side + "BarMaxChannel"] = maximum
    samples["centerLeftBoundary"] = at(159, HEIGHT // 2)
    samples["centerRightBoundary"] = at(1120, HEIGHT // 2)
    return samples


def generate(ffmpeg, directory):
    validated = {name: original_video(name) for name in SOURCES}
    entries = []
    for output_name, source_name, index, color in SPECS:
        source, video = validated[source_name]
        sample = video["samples"][index]
        require(sample["index"] == index and sample["presentedPtsUs"] >= 0, "Unknown/negative source PTS")
        source_rgb = rgb_frame(ffmpeg, frame_arguments(source, index))
        source_pixels = pixels(source_rgb, color)
        output = directory / output_name
        encode = ["-c:v", "mjpeg", "-q:v", "2", "-pix_fmt", "yuvj420p"] if output.suffix == ".jpg" else ["-c:v", "png", "-pix_fmt", "rgb24"]
        run(ffmpeg, frame_arguments(source, index) + encode + ["-fflags", "+bitexact", "-flags:v", "+bitexact", "-y", output])
        stored_rgb = rgb_frame(ffmpeg, BASE_ARGS + ["-i", output, "-map", "0:v:0", "-frames:v", "1", "-threads:v", "1"])
        stored_pixels = pixels(stored_rgb, color)
        error = sum((actual - expected) ** 2 for actual, expected in zip(stored_rgb, source_rgb)) / len(source_rgb)
        require(error <= 4, "Stored input cover no longer closely represents the selected actual source frame")
        if output.suffix == ".png":
            require(stored_rgb == source_rgb, "Lossless PNG input differs from the selected full decoded frame")
        entries.append({
            "asset": "motion_test/" + output_name, "sizeBytes": output.stat().st_size, "sha256": digest(output.read_bytes()),
            "sourceAsset": "motion_test/" + source_name, "sourceSha256": SOURCES[source_name],
            "sourceFrameIndex": index, "presentationTimestampUs": sample["presentedPtsUs"],
            "width": WIDTH, "height": HEIGHT, "color": color, "sourceDecodedRgbSha256": digest(source_rgb),
            "storedDecodedRgbSha256": digest(stored_rgb), "rgbMeanSquaredError": error,
            "sourcePixelChecks": source_pixels, "storedPixelChecks": stored_pixels,
        })
    manifest = {
        "purpose": "Original full-size actual-frame covers for controlled LIVE inputs; Android final exports remain under test",
        "generator": "scripts/generate_live_cover_fixtures.py", "generatorSha256": digest(Path(__file__).read_bytes()),
        "timingReader": "Independent ISO BMFF stts/edit-list parser in generate_fixed_motion_fixture.py; no Android code",
        "timingReaderSha256": digest((ROOT / "scripts/generate_fixed_motion_fixture.py").read_bytes()),
        "ffmpegVersion": run(ffmpeg, ["-version"]).decode("utf-8", "replace").splitlines()[0],
        "geometry": "1280x720 square pixels; original 960x720 color region and both 160-pixel black side bars retained",
        "selection": "Actual sequential decoder frame indices, no resizing, no seek-to-nearest-frame, no generated replacement image",
        "covers": entries,
    }
    (directory / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    require(args.ffmpeg.is_file(), "Pass an already installed FFmpeg executable")
    with tempfile.TemporaryDirectory(prefix="yingxia_live_input_") as temporary:
        directory = Path(temporary)
        manifest = generate(args.ffmpeg, directory)
        names = [spec[0] for spec in SPECS] + [MANIFEST_NAME]
        if args.check:
            for name in names:
                require((TARGET / name).is_file() and (TARGET / name).read_bytes() == (directory / name).read_bytes(),
                        f"Independent LIVE cover check differs: {name}")
        else:
            TARGET.mkdir(parents=True, exist_ok=True)
            for name in names:
                shutil.copyfile(directory / name, TARGET / name)
        for cover in manifest["covers"]:
            print(json.dumps({"checked" if args.check else "generated": cover["asset"], "sha256": cover["sha256"],
                              "frameIndex": cover["sourceFrameIndex"], "ptsUs": cover["presentationTimestampUs"],
                              "sizeBytes": cover["sizeBytes"], "rgbMeanSquaredError": cover["rgbMeanSquaredError"]}))


if __name__ == "__main__":
    main()
