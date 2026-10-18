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
   coordinates. With **capture coalescing** on (the default), tapping a monitor
   line interprets the whole group of consecutive captures that line belongs to —
   repeats of one transmission, or a frame split across captures — not just that
   single capture (see **Monitor capture** below).
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
default inter-frame gap (ms); the **OOK capture window** (min pulse / frame gap)
and **RSSI gate**; **capture coalescing** (combine consecutive captures + its time
window); plus USB adapter selection, radio interface (`r0`…), profile management
and the list of registered analyzers. The capture window, RSSI gate and
coalescing are described under **Monitor capture** below.

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

### Monitor capture (OOK window, RSSI gate, coalescing)

**OOK capture window.** Two parameters shape each OOK capture (firmware build 2+;
older firmware ignores them): **min pulse** — the shortest edge kept (trims runts)
— and **frame gap** (`maxGapUs`) — a continuous LOW this long ends a frame. The
firmware captures one aligned frame per gap, so set the frame gap *above* the
longest LOW inside a frame and *below* the silence between repeats, giving one
capture = one complete frame. The default is **15000 µs**. Avoid values near a
protocol's internal gap: an HCS/KeeLoq header gap is ~10 ticks ≈ **4000 µs at
Te=400**, so a frame gap around 4000 can cut an HCS frame in two (preamble in one
capture, data bits in the next) — which coalescing can still recover, but it is
cleaner not to split in the first place.

**RSSI gate.** A carrier-sense threshold in dBm: the firmware only starts a
capture when RSSI is at or above it (default **−85**). Lower it in a quiet RF
environment to catch weaker remotes; raise it where the noise floor is high. It is
sent as the optional 8th argument of `monitor start` and shown in Settings as a
magnitude (`−dBm`); firmware without it simply ignores the extra token.

**Capture coalescing.** The firmware reports every capture raw and paces them
~300 ms apart, so one button press (which repeats the same frame several times)
arrives as a short run of captures close in time. With coalescing on (Settings →
*Capture coalescing*, default on), the app groups consecutive captures of the same
interface and kind that arrive within the **group window** (default 500 ms);
a capture that arrives later than the window starts a new group ("too far apart" =
a different transmission). Tapping any line in a group interprets the whole group:

- **Decode-and-vote** — every capture is decoded; per analyzer the best-scoring
  match is kept and its field values are reconciled by majority vote across the
  repeats. This corrects a single capture's bit errors and raises confidence when
  the repeats agree.
- **Merged-pulse fallback** (OOK, ≥2 captures) — the captures are stitched back
  into one pulse-train, re-inserting the inter-capture gap the firmware dropped,
  so a single logical frame that was split across captures (e.g. an HCS frame cut
  at its header) decodes as a whole. Reassembly preserves bit alignment exactly,
  so recovered codes match the original.

Results from both are merged, highest score per analyzer winning, and ranked as
usual. Turn coalescing off to analyse each capture on its own (the pre-coalescing
behaviour). Synthetic signals (a loaded `.sub` or a composed protocol) are never
grouped — they always analyse on their own.

### OOK transmit method

Settings → *OOK transmit* chooses how re-generated OOK is keyed out:

- **GDO0 bit-bang** (`pulses play`, default) — the MCU toggles GDO0 and replays the
  exact captured/encoded microseconds.
- **Hardware FIFO** (`pulses playfifo`, firmware build 12+) — each pulse is quantised
  to `round(duration / unitUs)` bits and streamed through the TX FIFO, clocked by the
  radio's own bit-rate generator (no MCU busy-wait jitter). The app picks
  `unitUs` = the frame's averaged Te (robust short-pulse cluster), so a frame rebuilt
  from a decode — whose pulses are exact multiples of Te — reproduces losslessly, and
  a raw capture has its per-pulse jitter snapped onto the Te grid. The FIFO clocks
  bits at the radio's data rate; firmware build 12a+ derives that from `unitUs`
  (DRATE = round(1e6/unitUs), clamped) and restores the profile's rate after the
  burst, so the app passes only `unitUs` and does not touch `drate`.

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
| Monitor start/stop | `monitor start <if> <profile> json [minUs] [maxGapUs] [rssiGate]` / `monitor stop` |
| Transmit (FSK) | `radio <if> send <hex>` |
| Transmit (OOK), GDO0 bit-bang | `radio <if> pulses flush` → `pulses add …` → `pulses play 0 n [repeat] [gapUs]` |
| Transmit (OOK), hardware FIFO | `pulses flush` → `pulses add …` → `pulses playfifo 0 n <unitUs> [repeat] [gapUs]` (firmware derives/restores DRATE from `unitUs`) |

Every transmitted line respects the firmware CLI limits: command buffer
`CMD_MAX_BUFFER_LEN = 256` (line < 255 chars) and parser `PARSER_MAX_PARAMS = 64`
tokens. Long OOK pulse-trains (`pulses add`) are split so each command stays
within ≤250 chars **and** ≤60 values (64 − 4 prefix tokens). `send <hex>` is
rejected if it would exceed the buffer (instead of being silently truncated).

The `monitor start` trailing arguments are optional and positional — `minUs`
`maxGapUs` (the OOK capture window) then `rssiGate` (carrier-sense dBm). The app
only appends `rssiGate` once the window args are present, so older firmware that
doesn't parse the extra token keeps working. See **Monitor capture** above.

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

An analyzer only ever sees a single `CapturedPacket`; the *group* handling lives
above it. `CaptureCoalescer` buckets consecutive captures by time and
`CoalescedAnalysis` runs them — calling each analyzer per capture, voting on
fields, and (for OOK) re-running them on a reassembled train — then returns the
same `List<AnalysisResult>` the single-capture path returns. So a new analyzer
automatically benefits from voting and merge without any extra code.

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
KeeLoq codec, encoders, capture coalescing and the cross-capture vote/merge) is
compiled and round-trip tested — including that an HCS frame split across two
captures reassembles to the exact code, and that majority voting corrects a
corrupted repeat. The Compose/USB layer needs the Android SDK and real hardware —
verify the USB link and FSK packet profiles against a live signal (as the firmware
README advises for packet mode).
