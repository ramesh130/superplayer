# Session bundle format

What a SuperPlayer session trace bundle contains, line by line, and what it deliberately does not.

This is the document someone reads when a bundle lands on a bug report and they have never seen this
repository. It describes the artifact, not the library: everything below can be read with a text
editor and `grep`, and nothing in it requires the reader to know Kotlin, Media3 or Android.

A bundle is produced on the viewer's own device by the app that played the content
(`SessionBundle.Builder`, in `superplayer-diagnostics`), and it is written to be attachable to a
ticket without review: **no URL, host, path, query, token, request or response header, exception
message, session id, wall-clock time, device identity or DRM material can appear in one**, because
the recorder never holds those things rather than stripping them afterwards. What it does carry is
the timeline of one playback session and what the device it ran on can do. The rules are
[ADR-0015](adr/0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md)
rules 9 and 10 and the seven listed in `SessionTraceRecorder`'s own documentation;
[`docs/testing.md`](testing.md)'s *Golden traces* section is how they are held true.

---

## The shape of a bundle

Three header lines, then one fact per line, newline-terminated, UTF-8:

```
superplayer-session-bundle v1
superplayer-session-trace v1
timings relative-ms
+0 capability device apiLevel=35 lowRam=false heapBudgetMb=192
+0 capability decoder mime=video/avc secure=false instances=6 profile=8 level=2048 tunneling=false
+0 capability display shortEdgePx=2160 hdr=HDR10,HLG
+0 capability protection deliveredSecurityLevel=not-negotiated
+0 item id=series/expanse/s01e01 reason=PLAYLIST_CHANGED
+0 state BUFFERING
+50 load completed manifest durationMs=50
+50 bandwidth bitrateBps=11520
+250 state READY
+250 playing true
+8250 telemetry SessionEnded droppedEventCount=0
```

- **Line 1** names the artifact and the bundle's own format version.
- **Line 2** names the version of the *trace grammar* its fact lines are written in. A bundle is a
  session trace one layer richer, in the same grammar, so the two versions move independently: the
  bundle's changes when the artifact's own structure does, and the trace's when a fact line's
  meaning does.
- **Line 3** is `timings relative-ms` when every fact carries a time column, or `timings omitted`
  when the column has been dropped — the one normalisation applied to a trace captured on a real
  device, whose times are real and therefore differ between runs of the same session.
- **Every line after that** is one fact: `+<ms> <kind> <fields>`.
  - `+<ms>` is milliseconds since recording began, not a clock time. There is no date anywhere in a
    bundle.
  - `<kind>` says what kind of fact it is; the kinds are listed below.
  - `<fields>` are that kind's fields, in a fixed order, as `name=value` pairs or bare words.

No line depends on another to be read, so a bundle can be grepped by kind.

## Ordering

Lines are sorted, not in arrival order: by time, then by kind in the fixed rank below, then by what
the fact is (a load by its data type, track and media time), and only then by arrival. Two loads
that finished in the same millisecond therefore print in the same order however the device's threads
raced. **Arrival order is not a fact this format records.** The `capability` lines come first, all
stamped `+0`, because they describe the whole session rather than a moment in it.

Rank order: `capability`, `item`, `state`, `playing`, `tracks`, `discontinuity`, `load`, `bandwidth`,
`error`, `telemetry`.

## The kinds

### `capability` — what the device can do

Read once when the bundle is built. Four sub-kinds, each on its own line:

| Line | Fields |
| --- | --- |
| `capability device` | `apiLevel` (the Android API level), `lowRam` (whether the platform calls this a memory-constrained device), `heapBudgetMb` (the heap the app is allowed) |
| `capability decoder` | one per video MIME type the device declares a decoder for: `mime`, `secure` (whether a decoder able to work on protected memory is declared), `instances` (how many of that decoder can run at once), `profile` and `level` (the highest declared, as Android's own `MediaCodecInfo.CodecProfileLevel` numbers them), `tunneling` (whether tunneled playback is declared) |
| `capability display` | `shortEdgePx` (the shorter edge of the display's active mode, in physical pixels) and `hdr` (the HDR formats it shows, comma-separated) |
| `capability protection` | `deliveredSecurityLevel` — the Widevine security level the session's licences were delivered at, or `not-negotiated` for a session that never asked for a level below the one the device reports |

`unknown` means the platform did not answer, which is a different fact from `none`, which means it
answered and has none of them.

**What is not here, and will not be added.** No device make, model, product, board, fingerprint,
build id or serial; no decoder *component* name (`OMX.…`, `c2.…`), which names a vendor and usually a
chipset; no DRM identifier of any kind, the device's unique id above all; no display id or name; and
nothing of the network — no operator, SSID, address or interface. The test each field passed to be
here is that some code in the library reads it to decide something: a capability nothing reads is a
fingerprinting bit with a plausible excuse. This bounds each field rather than the aggregate: an API
level beside a display size beside a decoder table is still a coarse description of a class of
devices, and a reader of a bundle deserves to know that before attaching one.

### `item` — the content the player took on

`id` is the app's own content identifier, never a URL; `uri-withheld` appears instead where an app
identified its content by URL, and `none` where it gave no identifier. `reason` is why the item
changed (`PLAYLIST_CHANGED`, `AUTO`, `SEEK`, `REPEAT`).

### `state`, `playing` — the timeline

`state` is the engine's own playback state: `IDLE`, `BUFFERING`, `READY`, `ENDED`. `playing` is
`true` or `false` — whether the playhead is actually advancing, which is not the same thing as
`READY`.

### `tracks` — what is selected

`video=` and `audio=` carry the selected rendition's bitrate in bits per second, or `none`. A
sequence of these lines is the ABR decisions the session made, and a `telemetry TrackSwitched` line
carries the same switch with its direction.

### `discontinuity` — the playhead jumped

The reason (`SEEK`, `SEEK_ADJUSTMENT`, `AUTO_TRANSITION`, `SKIP`, `REMOVE`, `INTERNAL`,
`SILENCE_SKIP`) and `from=`/`to=` positions in media milliseconds.

### `load` — one transfer

`load <phase> <what> [<media time span>] [bitrate=…] [retry=…] [http=… | cause=…] [durationMs=…]`

- `<phase>` is `started`, `completed`, `canceled` or `error`.
- `<what>` is what was fetched, never where from: `manifest`, `media`, `init`, `drm` (a licence or
  provisioning exchange), `time-sync`, `ad`, or `other`, followed by `:video`, `:audio`, `:text` and
  so on where the transfer belonged to one kind of track.
- The media time span, `<start>-<end>` in milliseconds, is which part of the content it was.
- `retry=` appears where the transfer was an attempt after a failure.
- `http=` is the HTTP status on a refusal, and `cause=` is the failing exception's *class* where
  there was no status. **Never a message**: the failing URL is in the message of most such
  exceptions, which is why the class is printed instead.
- `durationMs=` is how long the transfer took, on the phases that have finished. A completed
  transfer carries no status because the engine's analytics report one only for a refusal.

### `bandwidth` — a throughput sample

`bitrateBps=` is the throughput of the transfer that just finished: its own bytes over its own
duration. These are the *samples*, not a smoothed estimate — a reader can see the dropout rather
than the average that hid it. A transfer with no measurable duration produces no sample.

### `error` — the engine failed

`code=` is the engine's own error-code name (`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` and its
siblings). What SuperPlayer made of that failure is a separate fact and arrives on the `telemetry`
line below as a `classification`.

### `telemetry` — the measured session

One line per event of SuperPlayer's own telemetry schema, printed as the event's name and its
fields: `SessionStarted`, `FirstFrameRendered`, `RebufferStarted`, `RebufferEnded`, `TrackSwitched`,
`SeekRequested`, `SeekCompleted`, `LiveLatencySampled`, `PlaybackStateSampled`,
`LicenceAcquisitionEnded`, `VideoFramesDropped`, `StartupFailed`, `MidStreamFailed`,
`DecisionChanged`, `SessionEnded`. **What each number means is
[`docs/telemetry-schema.md`](telemetry-schema.md)** — the same definitions a data pipeline is held
to, so a line here and a row in a warehouse are the same measurement. A failure prints its category,
the engine's code and SuperPlayer's own `classification`, and never a message.

`SessionEnded` carries `droppedEventCount`: telemetry delivery is bounded and lossy under pressure
by design, and this is how many events that session lost. A non-zero count means the bundle's
`telemetry` lines are incomplete, and it is the only honest way to say so.

## Versioning

Two versions, on the first two lines, and one rule for both: **a version moves when a line's meaning
changes, and not when a kind or a field is added.** So a reader can rely on a field's meaning within
a version, and a parser written against v1 keeps working when a kind it has never seen appears.
It is the same rule the telemetry schema's own version follows, for the same reason: a changed
denominator is a release note, an added field is not.

## What a bundle cannot tell you

- **Where anything came from.** No URL means no CDN, no rendition address, no cache host. A CDN's own
  access log has that half, and the join between the two is not offered here: the session id that
  would make it possible is exactly what a bundle leaves out.
- **What a failure said.** An exception's class, an HTTP status and SuperPlayer's classification are
  here; the message is not, because messages carry URLs.
- **Which device it was.** See `capability` above.
- **What was on screen.** Nothing renders a frame into a trace; dropped-frame counts are as close as
  it gets.

## Renderings

The facts are recorded once, in this format, and anything else — JSON, a Perfetto trace — is a
*rendering* of these lines rather than a second recording of the same session. That is deliberate:
two authored formats would be two implementations of the redaction rules above, of which one would
be behind within a release.
