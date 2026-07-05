# Claude Notes — ask Claude about your Supernote Manta handwriting

An Android app that runs **on the Supernote Manta itself**. Pick one of your
handwritten `.note` files, type a question, and Claude reads your handwriting
(vision OCR) and answers — streamed straight to the e-ink screen.

- **No exports, no PC**: `.note` files are parsed and rendered to images on
  the device by a Kotlin port of [supernotelib](https://github.com/jya-dev/supernote-tool)
  (X-series and X2-series formats, including the Manta's 1920×2560 pages).
- **Ask anything**: "Summarize Tuesday's meeting notes", "What did I write
  about the budget?", "Turn my todo scribbles into a clean list".
- **Transcribe**: one tap converts pages to clean Markdown text.
- **Follow-ups**: keep asking; page images are sent once and cached
  (Anthropic prompt caching), so follow-up questions are fast and cheap.
- **PDFs and images too**: PDFs in `Document/` can be asked about directly,
  and you can share images/PDFs from other apps into Claude Notes.
- E-ink friendly UI: high contrast, no animations.

## Install on the Manta

1. Grab `app-release.apk` (built by the *Build APK* GitHub Actions workflow —
   download the `claude-notes-apk` artifact from the latest run, or build
   locally, see below).
2. Sideload it on the Manta (Settings → My apps / sideloading, or
   `adb install app-release.apk` if you use adb).
3. Open the app → **Settings**:
   - Enter your Anthropic API key (create one at
     https://platform.claude.com/). Typing on e-ink is tedious, so you can
     instead copy a file named `claude_api_key.txt` into the tablet's storage
     root over USB and tap **Import from file**.
   - Pick a model (Claude Opus 4.8 by default).
4. Grant storage permission when asked, pick a notebook, ask away.

The device needs Wi-Fi while asking questions; your notes are sent to the
Anthropic API for the model to read (see their
[data usage policies](https://www.anthropic.com/legal/privacy)).

## Project layout

| Module | What it is | Where it runs |
|---|---|---|
| `notekit/` | Kotlin port of the Supernote `.note` parser/decoder/renderer | pure JVM — tested on any machine |
| `claudekit/` | Minimal Anthropic Messages API client (SSE streaming, vision, prompt caching, refusal fallbacks) | pure JVM — tested on any machine |
| `app/` | The Android app (file browser, ask screen, settings) | Supernote Manta (Android 11), min SDK 26 |
| `tools/make_golden.py` | Golden-data generator: synthesizes `.note` files and renders them with supernotelib | dev machine |

### Correctness of the .note port

`notekit` is verified pixel-for-pixel against supernotelib (the reference
implementation used by the Supernote community): `tools/make_golden.py`
synthesizes `.note` files covering the format's edge cases (RATTA_RLE
multibyte runs, the special blank-background block, X2 high-res grayscale
color codes and X-series compat codes, layer z-order and visibility,
base64 layer info, horizontal pages, custom PNG templates) and renders them
with supernotelib; the JVM test suite asserts identical output from the
Kotlin port. Regenerate with:

```sh
python3 -m venv .venv && .venv/bin/pip install supernotelib
.venv/bin/python tools/make_golden.py
```

## Building

```sh
# JVM modules + tests (no Android SDK needed)
./gradlew :notekit:test :claudekit:test

# The APK (needs the Android SDK; CI does this on every push)
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The release APK is signed with the committed `signing/sideload.jks`
(passwords: `sideload`). That keystore exists only so sideloaded updates
keep a stable signature — it protects nothing. Anyone can build an APK
signed with it, so only install builds you made yourself or that came from
your own repository's CI.

## Notes & limitations

- Notes made on the original (2019) Supernote A5/A6 use an older format and
  are not supported; X (A5X/A6X) and X2/N-series (Nomad, Manta) files are.
- Realtime-recognition text embedded in notes is ignored; Claude reads the
  actual handwriting, which also covers diagrams, tables and margin scrawl.
- A full Manta page is ~1500-4500 input tokens as an image. 10 pages with
  Opus 4.8 costs a few US cents per question; follow-ups reuse the cached
  images at ~10% of that.
- The API key is stored in the app's private preferences (not encrypted).
  Don't sideload this app on a device you don't trust.

## License

MIT (this repository). The `.note` format handling is ported from
[supernote-tool](https://github.com/jya-dev/supernote-tool)
(Apache License 2.0) — see headers in `notekit/` sources.
