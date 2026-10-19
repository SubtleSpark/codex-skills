# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Generate stable frame candidates; visual review is required before PPT creation."""

import argparse
import html
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


def positive(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError("must be finite and positive")
    return number


def difference(left, right):
    return sum(abs(a - b) for a, b in zip(left, right)) / len(left)


def stable_groups(samples, interval, threshold):
    """Compare against the group anchor so slow changes do not accumulate unseen."""
    anchor = None
    start = 0
    count = 0
    for index, frame in enumerate(samples):
        if anchor is None:
            anchor, start, count = frame, index, 1
        elif difference(anchor, frame) <= threshold:
            count += 1
        else:
            if count >= 2:
                yield start * interval, (start + count - 1) * interval
            anchor, start, count = frame, index, 1
    if count >= 2:
        yield start * interval, (start + count - 1) * interval


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("output", type=Path, help="new directory; existing paths are refused")
    parser.add_argument("--interval", type=positive, default=2,
                        help="sampling interval in seconds (default: 2); fast pages may be missed")
    parser.add_argument("--threshold", type=positive, default=6,
                        help="mean grayscale difference 0..255 (default: 6); lower finds subtler changes")
    parser.add_argument("--crop", help="fixed slide region W:H:X:Y in original pixels")
    args = parser.parse_args()
    if args.threshold > 255:
        parser.error("threshold must be <=255")
    crop = None
    if args.crop:
        if not re.fullmatch(r"\d+:\d+:\d+:\d+", args.crop):
            parser.error("crop must be numeric W:H:X:Y")
        crop = tuple(map(int, args.crop.split(":")))
        if min(crop[:2]) <= 0:
            parser.error("crop width and height must be positive")
    missing = [tool for tool in ("ffmpeg", "ffprobe") if not shutil.which(tool)]
    if missing:
        parser.exit(2, "Missing required tools: " + ", ".join(missing)
                    + ". Install using your machine's tool manager, then retry. Stopped.\n")
    video = args.video.resolve()
    if not video.is_file():
        parser.error("video does not exist")
    if args.output.exists():
        parser.error("output already exists; choose a new directory")
    probe = json.loads(run([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
        "format=duration:stream=width,height", "-of", "json", str(video)
    ], capture_output=True, text=True).stdout)
    streams = probe.get("streams", [])
    if not streams:
        parser.error("input has no video stream")
    duration = float(probe["format"]["duration"])
    if not math.isfinite(duration) or duration <= 0:
        parser.error("input duration is invalid")
    if crop:
        w, h, x, y = crop
        if x + w > streams[0]["width"] or y + h > streams[0]["height"]:
            parser.error("crop exceeds source dimensions")
    prefix = f"crop={args.crop}:exact=1," if crop else ""
    output = args.output.resolve()
    output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix="video-to-ppt-") as scratch:
        raw = Path(scratch) / "samples.gray"
        # round=up aligns the first sample bucket with time zero; start_time pins the grid.
        run(["ffmpeg", "-nostdin", "-v", "error", "-i", str(video), "-map", "0:v:0",
             "-vf", prefix + f"fps=1/{args.interval}:start_time=0:round=up,scale=96:54,format=gray",
             "-an", "-f", "rawvideo", "-pix_fmt", "gray", str(raw)])
        def samples():
            with raw.open("rb") as stream:
                while frame := stream.read(96 * 54):
                    if len(frame) != 96 * 54:
                        raise ValueError("truncated grayscale frame")
                    yield frame
        groups = list(stable_groups(samples(), args.interval, args.threshold))
    pages = []
    for start, end in groups:
        timestamp = min((start + end) / 2, max(0, duration - 0.001))
        filename = f"candidate-{len(pages) + 1:04d}.png"
        command = ["ffmpeg", "-nostdin", "-v", "error", "-ss", f"{timestamp:.6f}",
                   "-i", str(video), "-map", "0:v:0", "-frames:v", "1"]
        if crop:
            command += ["-vf", prefix.rstrip(",")]
        run(command + [str(output / filename)])
        if not (output / filename).is_file():
            raise ValueError(f"no image produced at {timestamp}")
        pages.append({"image": filename, "timestamp_seconds": timestamp,
                      "sample_start_seconds": start, "sample_end_seconds": end})
    manifest = {"video": str(video), "duration_seconds": duration,
                "interval_seconds": args.interval, "threshold": args.threshold,
                "crop": args.crop, "review_required": True, "candidates": pages}
    (output / "candidates.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    figures = "\n".join(
        f'<figure><a href="{p["image"]}"><img loading="lazy" src="{p["image"]}"></a>'
        f'<figcaption>{i + 1} · {p["timestamp_seconds"]:.2f}s</figcaption></figure>'
        for i, p in enumerate(pages))
    (output / "contact-sheet.html").write_text(
        '<!doctype html><meta charset="utf-8"><title>Frame candidates</title>'
        '<style>body{font:16px system-ui}main{display:grid;grid-template-columns:repeat(3,1fr)}'
        'figure{margin:8px}img{width:100%}</style>'
        f'<h1>{html.escape(video.name)}</h1><p>Candidates require visual review. '
        'Sampling may miss fast pages and subtle changes.</p><main>' + figures + '</main>')
    print(json.dumps({"output": str(output), "candidates": len(pages), "review_required": True}))
    if not pages:
        print("No stable candidates found. Inspect source and adjust sampling or crop.", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (subprocess.CalledProcessError, ValueError, KeyError, OSError) as error:
        print(f"Stopped: {error}. Existing output has been preserved.", file=sys.stderr)
        sys.exit(1)
