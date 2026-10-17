# cc1101-wizzard (Android app)

Android app (10+) for driving the **cc1101-wizzard v2** firmware on an RP2040
over USB/serial. It speaks the same CLI as a serial terminal
(`radio` / `profile` / `monitor` / `info`), parses raw monitor captures,
interprets them with pluggable protocol analyzers, and lets you edit and
transmit packets.

> A tool for working with your own RF hardware. Use it in line with your local
> ISM-band regulations. "Don't be evil."

## Building (APK)

Android Studio / Gradle project. It **needs to download dependencies**
(Google Maven, Maven Central, JitPack), so build it on your own machine.

Requirements:
- **JDK 17 or 21** (not 25 — Gradle 8.14.3 supports Java up to 24).
- **Android SDK** with `platforms;android-34` and `build-tools;34.x`.

Point the project at your SDK (either one):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"          # Linux
# or create local.properties:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

Build:

```bash
cd cc1101-wizzard-android
chmod +x gradlew                 # once, on Linux/macOS
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # build + install on a connected device
```

On Windows use `gradlew.bat assembleDebug`. If the wrapper reports an
incompatible JDK, point `JAVA_HOME` at a JDK 17/21 install.

| | |
|---|---|
| minSdk | 29 (Android 10) |
| target/compileSdk | 34 |
| Language / UI | Kotlin + Jetpack Compose (Material 3) |
| USB driver | `com.github.mik3y:usb-serial-for-android:3.8.1` |
| Gradle / AGP / Kotlin | 8.14.3 / 8.5.2 / 2.0.21 |

The phone must support **USB-OTG**. The RP2040 enumerates as CDC-ACM; Android
asks for USB permission on attach.

## Languages

The UI is localized through the standard Android resource mechanism:
**English is the default** (`res/values/strings.xml`) and **Polish** is provided
as an overlay (`res/values-pl/strings.xml`). Android picks the language from the
device/per-app locale automatically.

## Tabs

Tabs: **Monitor**, **Protocol**, **TX**, **cc1101**. Settings live behind the
gear icon in the top bar (a separate screen, not a tab).

1. **Monitor** — a **collapsible** connection panel (device identity from `info`,
   *Connect / Disconnect / Verify device*); collapse it with the chevron to give
   the log more room — a compact connection LED stays in the header. Below it are
   monitor start/stop and the **log** of all CLI + monitor traffic. Monitor lines
   use a distinct colour; the log is grouped into **signal blocks** — each capture
   starts a new block (with a divider above it) and its following lines share the
   block's faint background shade, with adjacent blocks alternating, so captures
   are easy to tell apart and tap. Tapping a line that carried a decodable packet
   opens it in the Protocol tab.
2. **Protocol** — entered from Monitor (tapping a log line) or from TX (after a
   protocol to transmit is selected — not from a file). Two modes: interpret a
   captured packet, and *compose* — edit a chosen protocol's fields and
   transmit. In interpret mode the decoded packet is run through every
   registered analyzer, ranked best-first; fields (codes, counters, timings,
   bytes) are editable and can be saved to `data/` (JSON + a human-readable
   `.txt`) and re-transmitted. If GPS tagging is enabled, saved packets include
   coordinates.
3. **TX** — pick a radio profile and frequency (433.92 / 433.42 / 315 MHz);
   load a signal from **disk** (file browser: `data/`, app-external dir, device
   storage, with up navigation), via **"Open .sub…"** (SAF, any location, e.g.
   Downloads) or from saved packets. Reads **Flipper Zero `.sub`** files (RAW and
   keyed). Scalable waveform preview (zoom buttons; drag to pan when zoomed in),
   an *Interpret* button, a repeat counter (1–100) and *Send*. Also **Transmit
   protocol**: a protocol list → fields (pre-initialised) appear for editing and
   sending; "Edit in Protocol" opens them in the Protocol tab. Repeat count and
   **inter-frame gap** are editable here; the gap in TX is a session override
   seeded from the config default (changing it does not modify the stored
   default — that is set in Settings). For OOK the firmware handles repeat/gap in
   a single `pulses play 0 n repeat gapUs` command (precise µs); for FSK the
   repeat is host-side (the firmware `send` has no repeat).
4. **cc1101** — modulation (2fsk/gfsk/ook/4fsk/msk), quick and generic radio
   parameter editing (`radio <iface> set …`), full radio state, and a profile
   editor (select / new / save / delete / apply).

**Settings** (gear icon): GPS tagging of packets, automatic reconnect, colour
theme (Graphite / Midnight Blue / Deep Purple / Forest / Amber Dark / Light),
default inter-frame gap (ms); plus USB adapter selection, radio interface
(`r0`…), profile management and the list of registered analyzers.

## "Right device" verification

Right after the USB link opens (and on *Verify*), the app sends **`info`** and
parses the JSON. If `device` is `cc1101-wizzard` the device is verified (green
indicator) and its identity (variant, FW version, build, platform, CLI API,
radio count) is shown on the Monitor tab. Interfaces (`r0`…) come from the
`interfaces` array. Older firmware without `info` is handled via `radio list`.

While the monitor is running the app blocks sending profiles and any other
command to the RP2040 — only `monitor stop` is allowed. The block is enforced in
the CLI layer and the cc1101 / TX / Settings controls are greyed out with a
banner.

### GPS

Enabling the toggle in Settings requests location permission. On save, the app
attaches the last known fix (framework `LocationManager`, no Google Play
Services) to the JSON and the `.txt` sidecar. No fix / no permission → saved
without coordinates (noted in the log).

### Flipper Zero `.sub`

The parser reads the header (`Filetype`, `Version`, `Frequency`, `Preset`,
`Protocol`). **RAW** files (`RAW_Data:` signed µs timings) map to a pulse-train
for replay. **Keyed** files (e.g. `Princeton`: `Bit`/`Key`/`TE`) are turned into
a bit string and a synthesised PWM signal, so they can also be interpreted and
transmitted.

## CLI mapping

| App action | CLI command |
|---|---|
| Verify device (identify) | `info` (JSON) |
| Interface list (fallback) | `radio list` |
| Radio state (cc1101 tab) | `radio <if> info` |
| Set parameter | `radio <if> set <param> <val>` |
| Set modulation | `radio <if> set mod <mod>` |
| RX / IDLE / RESET | `radio <if> rx` / `idle` / `reset` |
| Profiles | `profile list|show|new|add|del|apply` |
| Monitor start/stop | `monitor start <if> <profile> json` / `monitor stop` |
| Transmit (FSK) | `radio <if> send <hex>` |
| Transmit (OOK) | `radio <if> pulses flush` → `pulses add …` → `pulses play 0 n [repeat] [gapUs]` |

Every transmitted line respects the firmware CLI limits: command buffer
`CMD_MAX_BUFFER_LEN = 256` (line < 255 chars) and parser `PARSER_MAX_PARAMS = 64`
tokens. Long OOK pulse-trains (`pulses add`) are split so each command stays
within ≤250 chars **and** ≤60 values (64 − 4 prefix tokens). `send <hex>` is
rejected if it would exceed the buffer (instead of being silently truncated).

## Protocol analyzer API (plugins)

A new decoder is one interface implementation plus one registration:

```kotlin
class MyAnalyzer : ProtocolAnalyzer {
    override val id = "my_proto"
    override val displayName = "My protocol"
    override fun analyze(packet: CapturedPacket): AnalysisResult? {
        // packet is CapturedPacket.Ook (µs pulses) or CapturedPacket.Fsk (bytes)
        // return AnalysisResult(score, fields, encode{...}) or null if no match
    }
}

// at app start:
AnalyzerRegistry.register(MyAnalyzer())
```

`AnalysisResult.encode(fields)` builds a `TxFrame` (OOK pulses or FSK hex) from
the (possibly edited) fields — that is what gets transmitted.

**Confidence scoring is dynamic.** A match's score is a structural prior (how
distinctive the framing is) blended with the measured fit of the actual capture
— timing jitter against the Te grid, whether the frame closed on the expected
separator, and whether the exact bit count was recovered (`Scoring` in
`Protocol.kt`). So when more than one analyzer could claim a signal, the one the
pulses genuinely fit best wins, rather than a hand-tuned constant; generic
fallbacks keep a low flat score.

Built-in analyzers:

- **EV1527 / PT2262** (OOK, 24-bit PWM) — 20-bit address + 4-bit data.
- **PT2240** (OOK, 24-bit PWM) — short+gap preamble, editable 24-bit code.
- **KeeLoq family** — a generic OOK/PWM codec (`KeeLoqCodec`) with three
  implementations sharing it:
  - **HCS200** (66-bit): 32-bit hopping code (encrypted) + 28-bit serial +
    4-bit button + battery/repeat flags.
  - **HCS300** (66-bit): same on-air frame (counter lives inside the encrypted
    hopping code; serial/button/flags are cleartext).
  - **Nice Flor-S** (52-bit): best-effort field slice (serial / counter /
    button / flags); edit + replay round-trips exactly. Timing defaults may need
    tuning against a real capture.
  The encrypted/hopping portion is shown as captured — no KeeLoq decryption.
- **Somfy RTS** (OOK, Manchester, 640 µs) — hardware sync + soft-sync + 81
  payload bits, presented byte-aligned as **code0** (32-bit), **code1** (32-bit)
  and **code3** (24-bit). A **next_code** ticker (0–12) selects a rolling key:
  for a non-zero value `code0` is XORed by `next_key_xor_table[next_code]` before
  the frame is reassembled, then transmitted or saved. The payload is shown as
  captured (encrypted rolling code — not decrypted).
- **DB200** (OOK, 4-bit PWM).
- **Manchester (base)** — IEEE 802.3 Manchester decoder/encoder for OOK.
- **Generic OOK / FSK** fallbacks and a heuristic FSK rolling-code splitter.

## `data/` files

Saved packets are JSON files in the app's private `data` directory
(`filesDir/data`). Each file holds `analyzerId`, protocol name and the field
list — enough to rebuild and re-transmit the frame without the original capture
(`SavedEncoder`). A `.txt` sidecar is written alongside (with GPS if present).

**Export.** In the Protocol tab, each saved packet has an **export** (↥) action:
it opens the system folder picker (SAF) and copies that packet's `.json` and
`.txt` into the chosen folder (e.g. Downloads), outside the app's private
storage — no root or `adb` needed.

## Build/test note

The platform-independent logic (monitor parser, OOK decoders, analyzer API,
KeeLoq codec, encoders) is compiled and round-trip tested. The Compose/USB layer
needs the Android SDK and real hardware — verify the USB link and FSK packet
profiles against a live signal (as the firmware README advises for packet mode).
