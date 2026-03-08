#!/usr/bin/env python3
"""Generate Android launcher icons for all mipmap densities."""

from __future__ import annotations

import argparse
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except Exception as exc:  # pragma: no cover
    raise SystemExit(
        "Pillow is required. Install with: python -m pip install pillow"
    ) from exc

DENSITIES: dict[str, int] = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def _write_png(image: Image.Image, path: Path, size: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    resized = image.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(path, format="PNG")


def _round_icon(image: Image.Image) -> Image.Image:
    base = image.convert("RGBA")
    mask = Image.new("L", base.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, base.size[0], base.size[1]), fill=255)

    out = Image.new("RGBA", base.size, (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out


def _write_adaptive_xml(res_dir: Path) -> None:
    anydpi = res_dir / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)

    launcher_xml = """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">
    <background android:drawable=\"@color/ic_launcher_background\" />
    <foreground android:drawable=\"@mipmap/ic_launcher_foreground\" />
</adaptive-icon>
"""

    round_xml = """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">
    <background android:drawable=\"@color/ic_launcher_background\" />
    <foreground android:drawable=\"@mipmap/ic_launcher_round\" />
</adaptive-icon>
"""

    (anydpi / "ic_launcher.xml").write_text(launcher_xml, encoding="utf-8")
    (anydpi / "ic_launcher_round.xml").write_text(round_xml, encoding="utf-8")

    values = res_dir / "values"
    values.mkdir(parents=True, exist_ok=True)
    bg = """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources>
    <color name=\"ic_launcher_background\">#FFFFFF</color>
</resources>
"""
    (values / "ic_launcher_background.xml").write_text(bg, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--icon", required=True, help="Path to base icon")
    parser.add_argument("--round-icon", required=False, help="Path to round icon source")
    parser.add_argument("--res-dir", required=True, help="Android app src/main/res directory")
    args = parser.parse_args()

    icon_path = Path(args.icon).resolve()
    round_path = Path(args.round_icon).resolve() if args.round_icon else icon_path
    res_dir = Path(args.res_dir).resolve()

    if not icon_path.exists():
        raise SystemExit(f"Icon not found: {icon_path}")
    if not round_path.exists():
        raise SystemExit(f"Round icon not found: {round_path}")

    icon = Image.open(icon_path).convert("RGBA")
    round_icon = Image.open(round_path).convert("RGBA")

    rounded = _round_icon(round_icon)

    for density, size in DENSITIES.items():
        mipmap_dir = res_dir / f"mipmap-{density}"
        _write_png(icon, mipmap_dir / "ic_launcher.png", size)
        _write_png(rounded, mipmap_dir / "ic_launcher_round.png", size)
        _write_png(icon, mipmap_dir / "ic_launcher_foreground.png", size)

    _write_adaptive_xml(res_dir)


if __name__ == "__main__":
    main()
