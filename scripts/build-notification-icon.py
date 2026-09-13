#!/usr/bin/env python3
"""Derive the monochrome status-bar mark from the launcher artwork.

Android renders a notification's small icon as *alpha only*, tinted by the
system. The launcher mark is a full-colour bitmap whose opaque area is the
whole rounded square, so handing it over directly paints a white block — the
reason `AndroidNotificationSurface` shipped with a framework glyph instead.

What this does, in one pass over the artwork:

  1. ink   — opaque *and* dark. The mark is black line art on a white plate, so
             this drops the plate and keeps the drawing.
  2. close — dilate then erode. Hair is drawn as separate strands; without this
             they survive as unconnected specks that read as noise at 24 dp.
  3. fill  — close the gaps the closing left behind, by area. Small enclosed
             gaps are artefacts of step 2; the *large* one is the face, and
             filling it is what turns a portrait into a featureless blob.

The output is deliberately a silhouette, not a faithful reduction: at 24 dp the
line art is roughly four pixels of face, and an alpha-ramped downsample of it
measures as noise. Re-run after any change to the launcher artwork; the PNGs
are committed because the build has no image toolchain.
"""

from __future__ import annotations

import pathlib
from collections import deque

from PIL import Image, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/res/drawable-nodpi/hermes_desktop_icon.png"
NAME = "ic_stat_hermes.png"

# Status-bar icon sizes per density bucket, in pixels (24 dp).
DENSITIES = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}

WORK = 256          # resolution the morphology runs at
INK_LUMA = 128      # below this is drawing, above it is the plate
CLOSE_RADIUS = 6    # strand-bridging radius at WORK scale
MAX_GAP_AREA = 0.02  # enclosed gaps at or below this fraction are artefacts
CONTENT = 0.92      # glyph fraction of the canvas, leaving optical padding


def ink_mask(image: Image.Image) -> Image.Image:
    alpha = image.split()[3]
    luma = image.convert("L")
    mask = Image.new("L", image.size, 0)
    out, a, l = mask.load(), alpha.load(), luma.load()
    for y in range(image.height):
        for x in range(image.width):
            if a[x, y] > 128 and l[x, y] < INK_LUMA:
                out[x, y] = 255
    return mask


def closed(mask: Image.Image, radius: int) -> Image.Image:
    out = mask
    for _ in range(radius):
        out = out.filter(ImageFilter.MaxFilter(3))
    for _ in range(radius):
        out = out.filter(ImageFilter.MinFilter(3))
    return out


def fill_small_gaps(mask: Image.Image, max_fraction: float) -> Image.Image:
    width, height = mask.size
    source = mask.load()
    filled = mask.copy()
    target = filled.load()
    seen = [[False] * width for _ in range(height)]
    limit = max_fraction * width * height
    for seed_y in range(height):
        for seed_x in range(width):
            if source[seed_x, seed_y] != 0 or seen[seed_y][seed_x]:
                continue
            queue = deque([(seed_x, seed_y)])
            seen[seed_y][seed_x] = True
            region: list[tuple[int, int]] = []
            open_to_edge = False
            while queue:
                x, y = queue.popleft()
                region.append((x, y))
                if x in (0, width - 1) or y in (0, height - 1):
                    open_to_edge = True
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < width and 0 <= ny < height and not seen[ny][nx] and source[nx, ny] == 0:
                        seen[ny][nx] = True
                        queue.append((nx, ny))
            # The plate reaches the edge; the face does not and is too big to
            # be one of step 2's artefacts. Only the artefacts get filled.
            if not open_to_edge and len(region) <= limit:
                for x, y in region:
                    target[x, y] = 255
    return filled


def glyph() -> Image.Image:
    art = Image.open(SOURCE).convert("RGBA")
    mask = ink_mask(art)
    box = mask.getbbox()
    if box is None:
        raise SystemExit(f"{SOURCE} has no ink to trace")
    square = mask.crop(box).resize((WORK, WORK), Image.LANCZOS).point(lambda v: 255 if v > 96 else 0)
    return fill_small_gaps(closed(square, CLOSE_RADIUS), MAX_GAP_AREA)


def main() -> None:
    shape = glyph()
    for bucket, size in DENSITIES.items():
        inner = max(1, round(size * CONTENT))
        alpha = shape.resize((inner, inner), Image.LANCZOS)
        canvas = Image.new("L", (size, size), 0)
        offset = (size - inner) // 2
        canvas.paste(alpha, (offset, offset))
        # White everywhere; the system tints it and reads only the alpha.
        icon = Image.merge("RGBA", (Image.new("L", (size, size), 255),) * 3 + (canvas,))
        directory = ROOT / "app/src/main/res" / f"drawable-{bucket}"
        directory.mkdir(parents=True, exist_ok=True)
        icon.save(directory / NAME)
        print(f"ok    drawable-{bucket}/{NAME} {size}x{size}")


if __name__ == "__main__":
    main()
