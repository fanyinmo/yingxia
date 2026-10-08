# Original fixed motion input

These original assets replace only input preparation by `AlbumVideoComposer`:

| Asset | Timeline | Input helpers |
|---|---|---|
| `fixed_red_blue_2s.mp4` | red [0,1s), blue [1s,2s), 60 frames | VideoGif source, Gallery motionFixture, MotionPhoto video, GifDownload motionFixture, F19 LIVE input |
| `fixed_red_blue_4s.mp4` | red [0,2s), blue [2s,4s), 120 frames | ColdCdn createMp4 |
| `fixed_blue_red_4s.mp4` | blue [0,2s), red [2s,4s), 120 frames | F19 unknown-DYNAMIC input |

GIF conversion, MP4 remuxing, Motion Photo publication, and actual BGM
exports still run their production implementations and retain all assertions.
The unique yellow/magenta/cyan tail fixture continues to use its original real
animation converter.

Each input contains AVC baseline, 1280×720 square pixels, no rotation or B frames,
at 30 fps, with the exact timeline above. The original
4:3 color area remains 960×720 with 160-pixel black bars at both horizontal sides.
Generated 440 Hz mono AAC LC at 48 kHz covers the full presented two/four seconds.
AAC's 1024-sample priming is represented by an MP4 edit list; its media timeline
includes priming while its presented/container duration remains exactly 2/4 seconds.

The adjacent JSON records the exact FFmpeg version/options, generator hash, whole
asset SHA-256, compressed sample sizes/PTS, AVC config hash, AAC description hash,
all 60/120 independently decoded frame colors and frame digests, and decoded audio
coverage. These checks are performed on the host by a separate ISO BMFF reader
and FFmpeg, without using Android production media code. They do not establish
Android or native Gallery acceptance.

Rebuild/check using an existing FFmpeg executable with libx264/AAC:

```text
python scripts/generate_fixed_motion_fixture.py --ffmpeg <existing-ffmpeg-path>
python scripts/generate_fixed_motion_fixture.py --ffmpeg <existing-ffmpeg-path> --check
```

The default checks/rebuilds all three assets. `--asset red-blue-2s`,
`--asset red-blue-4s` or `--asset blue-red-4s` selects one.
The Phase 1 two-second asset is pinned to SHA-256
`2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57`;
the generator verifies that SHA and preserves its existing MP4 without writing it.

No dependency is downloaded or installed by the script. Use the recorded FFmpeg
7.1 build to reproduce the exact SHA; a different encoder/build can change bytes.
The Kotlin helpers pin the checked-in asset SHA so accidental changes cannot
silently replace the tested source. Rebuilding with a changed tool requires
reviewing the new manifest and updating the relevant fixture hashes deliberately.
`DynamicAlbumSaveTest` and every final SUT remain unchanged by these fixture substitutions.
