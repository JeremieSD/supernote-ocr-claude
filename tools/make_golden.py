#!/usr/bin/env python3
"""Generates golden test data for the notekit Kotlin port.

Synthesizes valid Supernote .note files covering the format's edge cases,
renders them with supernotelib (the reference implementation), and stores
both the .note files and the rendered PNGs as test resources. The Kotlin
port must reproduce supernotelib's output pixel for pixel.

Also generates raw RATTA_RLE stream/expected-output pairs to test the RLE
decoder against supernotelib's decoder byte for byte.

Usage: .venv/bin/python tools/make_golden.py
"""

import io
import json
import os
import random
import struct
import sys

from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import supernotelib as sn
from supernotelib.converter import ImageConverter
from supernotelib.decoder import RattaRleDecoder, RattaRleX2Decoder

OUT_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "notekit", "src", "test", "resources", "golden"
)

# RLE color codes
CODE_BLACK = 0x61
CODE_TRANSPARENT = 0x62
CODE_DARK_GRAY_X = 0x63       # X-series dark gray / X2 compat
CODE_GRAY_X = 0x64            # X-series gray / X2 compat
CODE_WHITE = 0x65
CODE_MARKER_BLACK = 0x66
CODE_DARK_GRAY_X2 = 0x9D
CODE_GRAY_X2 = 0xC9
CODE_MARKER_DARK_GRAY_X2 = 0x9E
CODE_MARKER_GRAY_X2 = 0xCA

SPECIAL_WHITE_BLOCK_SIZE = 0x140E


def encode_multibyte(code: int, n: int) -> bytes:
    """Two-pair encoding: decoded length = 1 + low + ((hi + 1) << 7).

    The first length byte is 0x80|hi and must not be 0xff (that's the
    special marker), so hi <= 0x7e i.e. q = hi + 1 <= 127; low may use the
    full 0..255 range. Valid n: 129..16512.
    """
    assert 129 <= n <= 16512, n
    q = min(127, (n - 1) // 128)
    low = (n - 1) - q * 128
    assert 0 <= low <= 255
    return bytes([code, 0x80 | (q - 1), code, low])


def rle_encode_run(code: int, n: int) -> bytes:
    """Encodes a single run of `n` pixels of `code` as RATTA_RLE."""
    out = bytearray()
    while n > 0:
        if n >= 0x4000:
            out += bytes([code, 0xFF])
            n -= 0x4000
        elif n > 128:
            out += encode_multibyte(code, n)
            n = 0
        else:
            out += bytes([code, n - 1])
            n = 0
    return bytes(out)


def rle_encode(codes: bytes) -> bytes:
    """RLE-encodes a row-major array of color codes."""
    out = bytearray()
    i = 0
    while i < len(codes):
        j = i
        while j < len(codes) and codes[j] == codes[i]:
            j += 1
        out += rle_encode_run(codes[i], j - i)
        i = j
    return bytes(out)


def make_blank_style_white_block(total_pixels: int) -> bytes:
    """Builds a BGLAYER block of exactly SPECIAL_WHITE_BLOCK_SIZE bytes that
    decodes to `total_pixels` white pixels.

    Uses 1283 multibyte runs (2 byte-pairs each) plus one single-pixel pair:
    2*1283 + 1 = 2567 pairs = 0x140e bytes.
    """
    m = (SPECIAL_WHITE_BLOCK_SIZE // 2 - 1) // 2  # 1283 multibyte runs
    base = (total_pixels - 1) // m
    extra = (total_pixels - 1) - base * m
    out = bytearray()
    for i in range(m):
        out += encode_multibyte(CODE_WHITE, base + (1 if i < extra else 0))
    out += bytes([CODE_WHITE, 0x00])  # single white pixel
    assert len(out) == SPECIAL_WHITE_BLOCK_SIZE, len(out)
    return bytes(out)


class NoteBuilder:
    """Writes a minimal but valid X2-series .note file."""

    def __init__(self, signature: str, equipment: str | None):
        self.buf = bytearray()
        self.buf += b"note"
        self.buf += signature.encode()
        self.header_params = {"MODULE_LABEL": "SNFILE_FEATURE", "FILE_TYPE": "NOTE"}
        if equipment:
            self.header_params["APPLY_EQUIPMENT"] = equipment
        self.pages = []  # list of page param dicts (with layer dicts)

    def _write_block(self, payload: bytes) -> int:
        address = len(self.buf)
        self.buf += struct.pack("<I", len(payload))
        self.buf += payload
        return address

    def _write_metadata(self, params: dict) -> int:
        text = "".join(f"<{k}:{v}>" for k, v in params.items())
        return self._write_block(text.encode())

    def add_page(self, layers: list[dict], page_params: dict) -> None:
        """layers: list of {name, protocol, bitmap(bytes)|None} bottom slot order
        MAINLAYER..BGLAYER; page_params: extra page metadata."""
        self.pages.append({"layers": layers, "params": page_params})

    def build(self) -> bytes:
        footer = {}
        page_addresses = []
        for page in self.pages:
            layer_addresses = {}
            for layer in page["layers"]:
                bitmap = layer.get("bitmap")
                bitmap_address = self._write_block(bitmap) if bitmap is not None else 0
                layer_params = {
                    "LAYERTYPE": layer.get("type", "NOTE"),
                    "LAYERPROTOCOL": layer.get("protocol", "RATTA_RLE"),
                    "LAYERNAME": layer["name"],
                    "LAYERBITMAP": str(bitmap_address),
                }
                layer_addresses[layer["name"]] = self._write_metadata(layer_params)
            page_params = dict(page["params"])
            for name in ["MAINLAYER", "LAYER1", "LAYER2", "LAYER3", "BGLAYER"]:
                page_params[name] = str(layer_addresses.get(name, 0))
            page_addresses.append(self._write_metadata(page_params))
        header_address = self._write_metadata(self.header_params)
        footer["FILE_FEATURE"] = str(header_address)
        for i, addr in enumerate(page_addresses):
            footer[f"PAGE{i + 1}"] = str(addr)
        footer_address = self._write_metadata(footer)
        return bytes(self.buf) + struct.pack("<I", footer_address)


def layer_info(entries: list[tuple[int, bool, bool]], b64: bool = False) -> str:
    """entries: (layerId, isBackgroundLayer, isVisible). Encoded like device
    files: JSON with ':' replaced by '#' (optionally base64 first)."""
    arr = [
        {"layerId": lid, "isBackgroundLayer": bg, "isVisible": vis}
        for (lid, bg, vis) in entries
    ]
    text = json.dumps(arr, separators=(",", ":"))
    if b64:
        import base64

        text = base64.b64encode(text.encode()).decode()
    return text.replace(":", "#")


def draw_codes(width: int, height: int, shapes) -> bytes:
    """Rasterizes rectangles of RLE color codes onto a transparent canvas.
    shapes: list of (code, left, top, w, h)."""
    canvas = bytearray([CODE_TRANSPARENT]) * (width * height)
    canvas = bytearray(CODE_TRANSPARENT for _ in range(width * height))
    for code, left, top, w, h in shapes:
        for y in range(top, min(top + h, height)):
            row = y * width
            for x in range(left, min(left + w, width)):
                canvas[row + x] = code
    return bytes(canvas)


def page_params(style: str, seq: list[str], info: str | None, orientation: str = "1000") -> dict:
    params = {
        "PAGESTYLE": style,
        "PAGESTYLEMD5": "0",
        "RECOGNSTATUS": "0",
        "ORIENTATION": orientation,
        "LAYERSEQ": ",".join(seq),
    }
    if info is not None:
        params["LAYERINFO"] = info
    return params


def build_variants() -> dict[str, bytes]:
    variants = {}

    # --- manta_basic: N5 (1920x2560), X2 colors + compat codes, blank white bg
    w, h = 1920, 2560
    b = NoteBuilder("SN_FILE_VER_20230015", "N5")
    main = draw_codes(
        w, h,
        [
            (CODE_BLACK, 100, 100, 700, 40),
            (CODE_DARK_GRAY_X2, 100, 200, 700, 40),
            (CODE_GRAY_X2, 100, 300, 700, 40),
            (CODE_WHITE, 100, 400, 700, 40),
            (CODE_MARKER_BLACK, 100, 500, 700, 40),
            (CODE_MARKER_DARK_GRAY_X2, 100, 600, 700, 40),
            (CODE_MARKER_GRAY_X2, 100, 700, 700, 40),
            (CODE_DARK_GRAY_X, 100, 800, 700, 40),   # compat codes
            (CODE_GRAY_X, 100, 900, 700, 40),
            (0x35, 100, 1000, 700, 40),              # unknown code -> literal gray on X2
            (CODE_BLACK, 40, 1100, 1, 1),            # single pixel
            (CODE_BLACK, 0, h - 1, w, 1),            # full-width run at the tail
        ],
    )
    b.add_page(
        [
            {"name": "MAINLAYER", "bitmap": rle_encode(main)},
            {"name": "BGLAYER", "bitmap": make_blank_style_white_block(w * h)},
        ],
        page_params(
            "style_white",
            ["MAINLAYER", "BGLAYER"],
            layer_info([(0, False, True), (0, True, True)]),
        ),
    )
    # second page: layers + z-order overlap + an invisible layer
    l1 = draw_codes(w, h, [(CODE_GRAY_X2, 300, 300, 900, 900)])
    l2 = draw_codes(w, h, [(CODE_BLACK, 500, 500, 900, 900)])
    main2 = draw_codes(w, h, [(CODE_DARK_GRAY_X2, 700, 700, 900, 900)])
    hidden = draw_codes(w, h, [(CODE_BLACK, 0, 0, w, h)])
    b.add_page(
        [
            {"name": "MAINLAYER", "bitmap": rle_encode(main2)},
            {"name": "LAYER1", "bitmap": rle_encode(l1)},
            {"name": "LAYER2", "bitmap": rle_encode(l2)},
            {"name": "LAYER3", "bitmap": rle_encode(hidden)},
            {"name": "BGLAYER", "bitmap": rle_encode_run(CODE_WHITE, w * h)},
        ],
        page_params(
            "style_white",
            ["LAYER2", "LAYER1", "MAINLAYER", "BGLAYER"],  # LAYER3 not in seq
            layer_info(
                [(0, False, True), (1, False, True), (2, False, True), (3, False, False), (0, True, True)],
                b64=True,
            ),
        ),
    )
    variants["manta_basic"] = b.build()

    # --- manta_horizontal: landscape orientation
    b = NoteBuilder("SN_FILE_VER_20230015", "N5")
    main = draw_codes(h, w, [(CODE_BLACK, 50, 50, 2000, 60), (CODE_GRAY_X2, 50, 200, 60, 1500)])
    b.add_page(
        [
            {"name": "MAINLAYER", "bitmap": rle_encode(main)},
            {"name": "BGLAYER", "bitmap": rle_encode_run(CODE_WHITE, w * h)},
        ],
        page_params(
            "style_white",
            ["MAINLAYER", "BGLAYER"],
            layer_info([(0, False, True), (0, True, True)]),
            orientation="1090",
        ),
    )
    variants["manta_horizontal"] = b.build()

    # --- manta_custom_bg: user_ PNG template with alpha
    b = NoteBuilder("SN_FILE_VER_20260016", "N5")
    img = Image.new("RGBA", (w, h), (255, 255, 255, 255))
    for y in range(0, h, 200):
        for x in range(w):
            img.putpixel((x, y), (200, 60, 60, 255))
    for x in range(0, w, 200):
        for y in range(h):
            img.putpixel((x, y), (60, 60, 200, 128))  # semi-transparent
    png_buf = io.BytesIO()
    img.save(png_buf, format="PNG")
    main = draw_codes(w, h, [(CODE_BLACK, 400, 400, 1000, 300)])
    b.add_page(
        [
            {"name": "MAINLAYER", "bitmap": rle_encode(main)},
            {"name": "BGLAYER", "bitmap": png_buf.getvalue()},
        ],
        page_params(
            "user_mytemplate",
            ["MAINLAYER", "BGLAYER"],
            layer_info([(0, False, True), (0, True, True)]),
        ),
    )
    variants["manta_custom_bg"] = b.build()

    # --- a5x_legacy: X-series (1404x1872), pre-highres signature and colors
    w2, h2 = 1404, 1872
    b = NoteBuilder("SN_FILE_VER_20220011", None)
    main = draw_codes(
        w2, h2,
        [
            (CODE_BLACK, 100, 100, 500, 30),
            (CODE_DARK_GRAY_X, 100, 200, 500, 30),
            (CODE_GRAY_X, 100, 300, 500, 30),
            (0x67, 100, 400, 500, 30),  # marker dark gray (X naming)
            (0x68, 100, 500, 500, 30),  # marker gray (X naming)
        ],
    )
    b.add_page(
        [
            {"name": "MAINLAYER", "bitmap": rle_encode(main)},
            {"name": "BGLAYER", "bitmap": rle_encode_run(CODE_WHITE, w2 * h2)},
        ],
        page_params(
            "style_white",
            ["MAINLAYER", "BGLAYER"],
            layer_info([(0, False, True), (0, True, True)]),
        ),
    )
    variants["a5x_legacy"] = b.build()

    return variants


def make_rle_stream_goldens(rng: random.Random) -> list[dict]:
    """Random valid RLE streams decoded by supernotelib's decoders."""
    cases = []
    codes_x2 = [0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x9D, 0xC9, 0x9E, 0xCA, 0x35, 0x7A]
    codes_x = [0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68]
    for case_index in range(24):
        highres = case_index % 2 == 0
        codes = codes_x2 if highres else codes_x
        width = rng.choice([64, 128, 331])
        height = rng.choice([32, 77, 256])
        total = width * height
        canvas = bytearray()
        while len(canvas) < total:
            run = min(
                total - len(canvas),
                rng.choice([1, 2, 7, 128, 129, 300, 0x400, 0x4000, 16512, 20000]),
            )
            canvas += bytes([rng.choice(codes)]) * run
        data = rle_encode(bytes(canvas))
        decoder = RattaRleX2Decoder() if highres else RattaRleDecoder()
        expected, size, bpp = decoder.decode(data, width, height)
        assert bpp == 8 and size == (width, height)
        cases.append(
            {
                "name": f"rle_{case_index:02d}",
                "highres": highres,
                "width": width,
                "height": height,
                "input": data,
                "expected": expected,
            }
        )
    # Tail-holder case: stream ends with a held multibyte pair.
    for case_index, highres in [(100, True), (101, False)]:
        width, height = 128, 64
        total = width * height
        head = rle_encode_run(0x61, total - 1024)  # leaves gap of 1024 = (7+1)<<7
        data = head + bytes([0x65, 0x80 | 0x07])
        decoder = RattaRleX2Decoder() if highres else RattaRleDecoder()
        expected, _, _ = decoder.decode(data, width, height)
        cases.append(
            {
                "name": f"rle_{case_index}",
                "highres": highres,
                "width": width,
                "height": height,
                "input": data,
                "expected": expected,
            }
        )
    return cases


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    rng = random.Random(20260705)

    manifest = {"notes": [], "rle": []}

    for name, blob in build_variants().items():
        note_path = os.path.join(OUT_DIR, f"{name}.note")
        with open(note_path, "wb") as f:
            f.write(blob)
        notebook = sn.load_notebook(note_path)
        converter = ImageConverter(notebook)
        pages = []
        for p in range(notebook.get_total_pages()):
            img = converter.convert(p)
            png_name = f"{name}_p{p}.png"
            img.save(os.path.join(OUT_DIR, png_name))
            pages.append(png_name)
        manifest["notes"].append({"file": f"{name}.note", "pages": pages})
        print(f"{name}: {notebook.get_total_pages()} page(s), {len(blob)} bytes")

    for case in make_rle_stream_goldens(rng):
        in_name = f"{case['name']}.in"
        out_name = f"{case['name']}.out"
        with open(os.path.join(OUT_DIR, in_name), "wb") as f:
            f.write(case["input"])
        with open(os.path.join(OUT_DIR, out_name), "wb") as f:
            f.write(case["expected"])
        manifest["rle"].append(
            {
                "input": in_name,
                "expected": out_name,
                "highres": case["highres"],
                "width": case["width"],
                "height": case["height"],
            }
        )
    with open(os.path.join(OUT_DIR, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"wrote golden data to {OUT_DIR}")


if __name__ == "__main__":
    main()
