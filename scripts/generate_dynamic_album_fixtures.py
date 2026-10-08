"""Generate tiny, original animation fixtures; Pillow is needed only to regenerate tests."""
from pathlib import Path
from PIL import Image


def main():
    root = Path(__file__).resolve().parents[1]
    target = root / "app/src/androidTest/assets/dynamic_album_test"
    target.mkdir(parents=True, exist_ok=True)
    frames = [Image.new("RGB", (48, 48), color) for color in ("red", "blue")]
    frames[0].save(target / "two_frames.gif", save_all=True, append_images=frames[1:], duration=250, loop=0)
    frames[0].save(target / "two_frames.webp", save_all=True, append_images=frames[1:], duration=250, loop=0, lossless=True)
    frames[0].save(target / "two_frames.png", save_all=True, append_images=frames[1:], duration=250, loop=0)
    frames[0].save(target / "static_cover.png")
    for frame in frames:
        frame.close()


if __name__ == "__main__":
    main()
