# KokoReader — on-device screen reader (MVP)

English-only, fully on-device screen reader for Android 10+ (API 29+).
Tap the floating 🔊 button to OCR the current screen (ML Kit) and speak it
(Kokoro-82M INT8 via ONNX Runtime, espeak-ng G2P via JNI). ⏭ reads the next
page. **Manual page-turn only — no auto-swipe in v1.** No text is persisted.

## Pipeline

```
MediaProjection frame → ImageReader → ML Kit Latin OCR (RAM only)
  → espeak-ng G2P (JNI, ASCII fold stub in v1) → phoneme ids
  → kokoro-82m-int8.onnx (ONNX Runtime) → 24kHz PCM → AudioTrack stream
```

## Build

Prereqs: JDK 17, Android SDK (platform 34, build-tools 34.0.0, NDK r27 for the JNI lib).

```bash
cd kokoreader
# point Gradle at your SDK (one of):
echo "sdk.dir=/path/to/android-sdk" > local.properties
# ...or export ANDROID_HOME / ANDROID_SDK_ROOT
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

A `gradlew` wrapper script is NOT bundled (no network bootstrap in repo);
use any Gradle 8.7+ install, or generate the wrapper once:
`gradle wrapper --gradle-version 8.9` then `./gradlew assembleDebug`.

## Install (sideload)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone:
1. Open **KokoReader**, tap **Start reading**.
2. Grant **Display over other apps** (floating button) when prompted.
3. Accept the **screen-capture consent** dialog (required every service start — Android rule).
4. Open any app with English text, tap **🔊** on the floating button. Tap again to stop. **⏭** reads the next page.

## Adding the Kokoro model

The repo ships **without** `kokoro-82m-int8.onnx` (size/licensing). Without it
the app builds and runs OCR-only (TTS logs + no-ops). To enable voice:

1. Export Kokoro-82M to ONNX with INT8 dynamic quantization
   (input: `phoneme_ids [1, N] int64`; output: `audio [1, T] float32`, 24 kHz).
2. Copy it to `app/src/main/assets/models/kokoro-82m-int8.onnx`
   (replacing nothing — the `.STUB` file next to it is documentation only).
   The app refuses stub-sized/placeholder files at runtime.
3. Rebuild. The model is memory-mapped from APK assets — no download, no network.

## v2 notes

- Link prebuilt `libespeak-ng` under `app/src/main/cpp/espeak-ng/` and replace
  the ASCII-fold stub in `espeak_bridge.cpp` with `espeak_TextToPhonemes` IPA output
  mapped to Kokoro phone ids.
- Wire the voice style vector (2nd ONNX input) for voice selection.
- Optional: auto-page-turn via accessibility scroll events (explicitly out of v1 scope).
