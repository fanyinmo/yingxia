"""Rebuild/check an original test input without calling any Android production code.

Requires an existing FFmpeg with libx264/AAC; never downloads or installs tools.
Usage: python scripts/generate_fixed_motion_fixture.py --ffmpeg /path/to/ffmpeg
       python scripts/generate_fixed_motion_fixture.py --ffmpeg /path/to/ffmpeg --check
Use --asset red-blue-2s, red-blue-4s or blue-red-4s to select one input; default is all.
The MP4/manifest are test inputs, never precomputed results of a tested export.
"""
import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/androidTest/assets/motion_test"
PINNED_TWO_SECOND_SHA = "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57"
SPECS = {
    "red-blue-2s": {"name": "fixed_red_blue_2s.mp4", "segmentSeconds": 1, "colors": ("red", "blue")},
    "red-blue-4s": {"name": "fixed_red_blue_4s.mp4", "segmentSeconds": 2, "colors": ("red", "blue")},
    "blue-red-4s": {"name": "fixed_blue_red_4s.mp4", "segmentSeconds": 2, "colors": ("blue", "red")},
}
WIDTH, HEIGHT = 1280, 720
FILTER = (
    "[0:v]pad=1280:720:160:0:black,setsar=1[r];"
    "[1:v]pad=1280:720:160:0:black,setsar=1[b];"
    "[r][b]concat=n=2:v=1:a=0,format=yuv420p[v]"
)
ENCODE_ARGS = [
    "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
    "-f", "lavfi", "-i", "color=c=0xdc2828:s=960x720:r=30:d=1",
    "-f", "lavfi", "-i", "color=c=0x283cdc:s=960x720:r=30:d=1",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
    "-filter_complex", FILTER, "-map", "[v]", "-map", "2:a:0", "-t", "2",
    "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-profile:v", "baseline",
    "-level:v", "3.1", "-pix_fmt", "yuv420p", "-bf", "0", "-g", "30",
    "-keyint_min", "30", "-sc_threshold", "0", "-threads:v", "1",
    "-c:a", "aac", "-b:a", "96k", "-ac", "1", "-ar", "48000", "-threads:a", "1",
    "-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact",
    "-map_metadata", "-1", "-video_track_timescale", "30000", "-movflags", "+faststart",
]


def encode_arguments(spec):
    # The two-second argument vector stays byte-for-byte identical to Phase 1.
    segment = spec["segmentSeconds"]
    duration = segment * 2
    colors = {"red": "0xdc2828", "blue": "0x283cdc"}
    replacements = {
        "color=c=0xdc2828:s=960x720:r=30:d=1": f"color=c={colors[spec['colors'][0]]}:s=960x720:r=30:d={segment}",
        "color=c=0x283cdc:s=960x720:r=30:d=1": f"color=c={colors[spec['colors'][1]]}:s=960x720:r=30:d={segment}",
        "sine=frequency=440:sample_rate=48000:duration=2": f"sine=frequency=440:sample_rate=48000:duration={duration}",
    }
    arguments = [replacements.get(argument, argument) for argument in ENCODE_ARGS]
    arguments[arguments.index("-t") + 1] = str(duration)
    return arguments


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(executable, args):
    result = subprocess.run([str(executable), *map(str, args)], capture_output=True, timeout=90)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace"))
    return result


def boxes(data):
    """Bounded ISO BMFF atom reader, separate from Android's MediaExtractor."""
    cursor = 0
    while cursor < len(data):
        require(cursor + 8 <= len(data), "Truncated atom header")
        size, kind = struct.unpack_from(">I4s", data, cursor)
        header = 8
        if size == 1:
            require(cursor + 16 <= len(data), "Truncated extended atom header")
            size = struct.unpack_from(">Q", data, cursor + 8)[0]
            header = 16
        elif size == 0:
            size = len(data) - cursor
        require(header <= size <= len(data) - cursor, "Invalid atom size")
        yield kind.decode("ascii"), data[cursor + header:cursor + size]
        cursor += size


def child(data, kind):
    matches = [payload for name, payload in boxes(data) if name == kind]
    require(len(matches) == 1, f"Expected one {kind} atom")
    return matches[0]


def clock_header(data):
    require(data[0] == 0, "Fixture requires version-zero movie/media clocks")
    return struct.unpack_from(">II", data, 12)  # timescale, duration


def expanded_timing(stbl):
    stts = child(stbl, "stts")
    count = struct.unpack_from(">I", stts, 4)[0]
    require(len(stts) == 8 + count * 8, "Invalid stts table")
    durations = []
    for offset in range(8, len(stts), 8):
        samples, delta = struct.unpack_from(">II", stts, offset)
        require(0 < samples <= 1000 and delta > 0, "Invalid sample timing")
        durations.extend([delta] * samples)
    return durations


def track_manifest(trak, movie_scale):
    tkhd = child(trak, "tkhd")
    require(tkhd[0] == 0, "Fixture requires version-zero track header")
    matrix = list(struct.unpack_from(">9i", tkhd, 40))
    require(matrix == [65536, 0, 0, 0, 65536, 0, 0, 0, 1073741824], "Nonzero rotation/skew")
    track_duration = struct.unpack_from(">I", tkhd, 20)[0]
    mdia = child(trak, "mdia")
    scale, duration = clock_header(child(mdia, "mdhd"))
    handler = child(mdia, "hdlr")[8:12].decode("ascii")
    stbl = child(child(mdia, "minf"), "stbl")
    timing = expanded_timing(stbl)
    stsz = child(stbl, "stsz")
    fixed_size, count = struct.unpack_from(">II", stsz, 4)
    sizes = [fixed_size] * count if fixed_size else list(struct.unpack_from(f">{count}I", stsz, 12))
    require(len(timing) == count and all(size > 0 for size in sizes), "Empty/missing encoded samples")
    require(not any(kind == "ctts" for kind, _ in boxes(stbl)), "Fixture must have no B-frame offsets")
    samples, timestamp = [], 0
    for index, (delta, size) in enumerate(zip(timing, sizes)):
        samples.append({"index": index, "ptsTicks": timestamp, "durationTicks": delta, "sizeBytes": size})
        timestamp += delta
    require(timestamp == duration, "Media duration does not match actual samples")
    description = child(stbl, "stsd")
    require(struct.unpack_from(">I", description, 4)[0] == 1, "Multiple sample descriptions")
    codec, entry = next(boxes(description[8:]))
    result = {
        "handler": handler, "codec": codec, "timescale": scale,
        "mediaDurationTicks": duration, "trackDurationMovieTicks": track_duration,
        "presentedDurationUs": track_duration * 1_000_000 // movie_scale,
        "sampleCount": count, "samples": samples, "rotationDegrees": 0,
    }
    edits = [payload for kind, payload in boxes(trak) if kind == "edts"]
    if edits:
        elst = child(edits[0], "elst")
        require(elst[0] == 0 and struct.unpack_from(">I", elst, 4)[0] == 1, "Unexpected edit list")
        edit_duration, media_start, rate, fraction = struct.unpack_from(">Iihh", elst, 8)
        require(rate == 1 and fraction == 0, "Non-unit media rate")
        result["edit"] = {"durationMovieTicks": edit_duration, "mediaStartTicks": media_start}
        for sample in samples:
            sample["presentedPtsUs"] = round((sample["ptsTicks"] - media_start) * 1_000_000 / scale)
    else:
        for sample in samples:
            sample["presentedPtsUs"] = round(sample["ptsTicks"] * 1_000_000 / scale)
    if handler == "vide":
        result["width"], result["height"] = struct.unpack_from(">HH", entry, 24)
        avcc = child(entry[78:], "avcC")
        result["avcProfileIdc"], result["avcLevelIdc"] = avcc[1], avcc[3]
        result["avcConfigSha256"] = digest(avcc)
        pasp = [payload for kind, payload in boxes(entry[78:]) if kind == "pasp"]
        result["sampleAspectRatio"] = list(struct.unpack(">II", pasp[0])) if pasp else [1, 1]
        stss = child(stbl, "stss")
        sync_count = struct.unpack_from(">I", stss, 4)[0]
        result["syncSampleIndices"] = [i - 1 for i in struct.unpack_from(f">{sync_count}I", stss, 8)]
    elif handler == "soun":
        result["channels"] = struct.unpack_from(">H", entry, 16)[0]
        result["sampleRate"] = struct.unpack_from(">I", entry, 24)[0] >> 16
        result["audioDescriptionSha256"] = digest(entry[28:])
    return result


def inspect(executable, source, spec):
    duration_seconds = spec["segmentSeconds"] * 2
    duration_us = duration_seconds * 1_000_000
    frame_count = duration_seconds * 30
    pcm_count = duration_seconds * 48000
    data = source.read_bytes()
    moov = child(data, "moov")
    movie_scale, movie_duration = clock_header(child(moov, "mvhd"))
    tracks = [track_manifest(payload, movie_scale) for kind, payload in boxes(moov) if kind == "trak"]
    require([track["handler"] for track in tracks] == ["vide", "soun"], "Expected one AVC and one AAC track")
    video, audio = tracks
    require(movie_duration * 1_000_000 // movie_scale == duration_us, "Container duration differs from the source contract")
    require(video["codec"] == "avc1" and video["width"] == WIDTH and video["height"] == HEIGHT, "Wrong video format")
    require(video["sampleAspectRatio"] == [1, 1] and video["avcProfileIdc"] == 66, "Expected square-pixel AVC baseline")
    require(video["timescale"] == 30000 and video["mediaDurationTicks"] == duration_seconds * 30000, "Wrong video duration")
    require(video["presentedDurationUs"] == duration_us and video["edit"]["mediaStartTicks"] == 0, "Wrong video presentation start/duration")
    require(video["sampleCount"] == frame_count and video["syncSampleIndices"] == list(range(0, frame_count, 30)), "Missing samples or expected IDRs")
    require([sample["ptsTicks"] for sample in video["samples"]] == list(range(0, duration_seconds * 30000, 1000)), "Missing/reordered AVC samples")
    require(all(sample["sizeBytes"] < 1024 * 1024 for sample in video["samples"]), "A frame exceeds existing remux buffer")
    require(audio["codec"] == "mp4a" and audio["sampleRate"] == 48000 and audio["channels"] == 1, "Wrong AAC format")
    require(audio["presentedDurationUs"] == duration_us, "AAC must cover the whole video")
    require(audio["edit"]["mediaStartTicks"] == 1024, "AAC priming must be removed by its edit list")
    require(audio["samples"][-1]["ptsTicks"] + audio["samples"][-1]["durationTicks"] - 1024 == pcm_count, "AAC ending is truncated")

    # Decode every actual AVC frame sequentially; no nearest-frame query or Android production helper.
    raw = run(executable, ["-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
        "-map", "0:v:0", "-an", "-fps_mode", "passthrough", "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"]).stdout
    frame_bytes = WIDTH * HEIGHT * 3
    require(len(raw) == frame_count * frame_bytes, "Actual decoder did not emit every source frame")
    colors = []
    for index in range(frame_count):
        base = index * frame_bytes
        center = base + ((HEIGHT // 2) * WIDTH + WIDTH // 2) * 3
        rgb = list(raw[center:center + 3])
        expected = spec["colors"][0] if index < spec["segmentSeconds"] * 30 else spec["colors"][1]
        require((rgb[0] > rgb[2] + 80) if expected == "red" else (rgb[2] > rgb[0] + 80), "Actual decoded color order is wrong")
        require((rgb[0] > 200 and rgb[2] < 60) if expected == "red" else (rgb[2] > 200 and rgb[0] < 60), "Fixture cannot meet existing GIF color assertions")
        for x in (0, 159, 1120, 1279):
            position = base + ((HEIGHT // 2) * WIDTH + x) * 3
            require(max(raw[position:position + 3]) <= 8, "FIT black bars were lost")
        for x in (160, 1119):
            position = base + ((HEIGHT // 2) * WIDTH + x) * 3
            edge = raw[position:position + 3]
            require((edge[0] > edge[2] + 80) if expected == "red" else (edge[2] > edge[0] + 80), "The original 960-pixel color region was resized")
        colors.append({"index": index, "ptsUs": video["samples"][index]["presentedPtsUs"], "color": expected, "centerRgb": rgb})
    md5 = run(executable, ["-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
        "-map", "0:v:0", "-an", "-fps_mode", "passthrough", "-f", "framemd5", "pipe:1"]).stdout.decode("ascii")
    require(len([line for line in md5.splitlines() if line and not line.startswith("#")]) == frame_count, "Incomplete independent frame digest")
    pcm = run(executable, ["-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
        "-map", "0:a:0", "-vn", "-t", str(duration_seconds), "-ac", "1", "-ar", "48000", "-f", "s16le", "pipe:1"]).stdout
    require(len(pcm) == pcm_count * 2, "Actual AAC decode does not cover the whole source duration")
    samples = array.array("h", pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    rms = [math.sqrt(sum(value * value for value in samples[i:i + 4800]) / 4800) for i in range(0, pcm_count, 4800)]
    require(all(value > 100 for value in rms), "Decoded AAC contains a silent/truncated 100 ms window")
    return {
        "asset": f"motion_test/{spec['name']}", "sizeBytes": len(data), "sha256": digest(data),
        "movieTimescale": movie_scale, "movieDurationTicks": movie_duration, "tracks": tracks,
        "independentSequentialDecode": {"frameCount": frame_count, "frames": colors, "framemd5": md5,
            "decodedAudioSamples": len(samples), "audio100msRms": rms, "pcmSha256": digest(pcm)},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", type=Path, default=None)
    parser.add_argument("--asset", choices=["all", *SPECS], default="all")
    parser.add_argument("--check", action="store_true", help="Verify the checked-in asset/manifest; do not rewrite them")
    args = parser.parse_args()
    executable = args.ffmpeg or shutil.which("ffmpeg")
    require(executable, "Pass --ffmpeg pointing to an already installed FFmpeg; no tool is installed by this script")
    selected = SPECS.items() if args.asset == "all" else [(args.asset, SPECS[args.asset])]
    for key, spec in selected:
        source = TARGET / spec["name"]
        manifest_path = source.with_suffix(".json")
        arguments = encode_arguments(spec)
        if args.check:
            observed = inspect(executable, source, spec)
            expected = json.loads(manifest_path.read_text(encoding="utf-8"))
            require(observed == expected["validation"], "Asset/decode differs from the independent checked-in manifest")
        else:
            TARGET.mkdir(parents=True, exist_ok=True)
            # Encode/verify outside the asset directory first; never overwrite an asset with invalid data.
            with tempfile.TemporaryDirectory(prefix="douyin_fixed_fixture_") as directory:
                generated = Path(directory) / spec["name"]
                run(executable, [*arguments, generated])
                observed = inspect(executable, generated, spec)
                if key == "red-blue-2s":
                    require(observed["sha256"] == PINNED_TWO_SECOND_SHA, "Existing Phase 1 two-second bytes must not change")
                    if source.exists():
                        require(digest(source.read_bytes()) == PINNED_TWO_SECOND_SHA, "Existing Phase 1 asset differs; refusing to replace it")
                version = run(executable, ["-version"]).stdout.decode("utf-8", "replace")
                segment = spec["segmentSeconds"]
                duration = segment * 2
                manifest = {
                    "purpose": "Original controlled input for test fixture helpers only; final production exports remain under test",
                    "geometry": f"1280x720 square pixels, 960x720 4:3 color region, 160 black pixels at each horizontal side; {spec['colors'][0]} [0,{segment}s), {spec['colors'][1]} [{segment},{duration}s)",
                    "audio": f"Original generated 440 Hz sine, AAC LC mono 48 kHz, full presented duration {duration} seconds",
                    "generator": "scripts/generate_fixed_motion_fixture.py", "generatorSha256": digest(Path(__file__).read_bytes()),
                    "ffmpegVersion": version, "ffmpegArguments": [*arguments, "<output.mp4>"],
                    "references": ["https://ffmpeg.org/ffmpeg-filters.html", "https://ffmpeg.org/ffmpeg-codecs.html", "https://ffmpeg.org/ffmpeg-formats.html"],
                    "validation": observed,
                }
                # Keep the existing Phase 1 MP4 untouched, even when bytes match exactly.
                if key != "red-blue-2s" or not source.exists():
                    source.write_bytes(generated.read_bytes())
                manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if key == "red-blue-2s":
            require(observed["sha256"] == PINNED_TWO_SECOND_SHA, "Existing Phase 1 two-second bytes must not change")
        print(json.dumps({"asset": observed["asset"], "sha256": observed["sha256"], "sizeBytes": observed["sizeBytes"], "videoFrames": spec["segmentSeconds"] * 60, "presentedDurationUs": spec["segmentSeconds"] * 2_000_000, "checked": args.check}))


if __name__ == "__main__":
    main()
