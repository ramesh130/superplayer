# The stream doctor

What `MediaSourceDoctor` reports about a stream, what each defect means, what produces it, and what
to change on the packager or the CDN to make it go away.

This is the document someone reads when their stream will not play, or plays worse than it should,
and they have never seen this repository. Every defect below is named by a stable id that a log line,
a support ticket and this library's test corpus share, and every one carries the clause it departs
from. The rules themselves are
[ADR-0015](adr/0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md);
this is what they say in words.

---

## What the doctor does, and what it deliberately does not

**It names defects and repairs nothing.** A report is a list of facts about a stream: the defect, how
far it departs from what conforming content does, the clause it departs from, and the
misconfiguration that produces it. Nothing in the doctor rewrites a manifest, retries a request,
chooses a rendition or changes what a player will do with the stream. A stream that is blocking in a
report plays exactly as badly after the report as before it — the fix is on the packager or the CDN,
and the report exists to say which.

That separation is deliberate, and it is ADR-0015 rule 4: **a pathology is not a failure.** A defect
of a stream and an end to a session are different facts with different vocabularies. A `Finding`
carries no failure class, no category, no retryability and no fallback rung, because most defects end
no session at all — the stream plays, and something about it is worse than it should be. What a
failed session ended on is `superplayer-resilience`'s `FailureClass`, which the library's own
fallback ladder acts on; the doctor holds no taxonomy with which to recompute one. When a report
carries both, they are printed side by side and neither is mapped onto the other (rule 5) — see
*Reading a postmortem* below. The reasons behind that separation are ADR-0015's; what it means for a
reader is that a report's findings are facts about the stream and nothing else.

Two further limits are worth knowing before reading a report:

- **It fetches manifests and downloads no segment** (rule 7). Every defect below is read from what a
  document *declares*, from the headers a manifest arrived with, or from the relation between the two
  — never from weighing media. The one exception is a single **headers-only probe** of one segment
  (rule 7's #289 addendum): one byte is asked for, nothing is read, and it is spent only by a rule
  that already has a defect to attribute.
- **It answers for the players the app it was built with builds.** The fetch travels the same
  transfer chain — the same cache, the same credential — that a player of that request would load
  through, so two apps can correctly be told different things about one manifest. The report says
  which of those layers it travelled (`DiagnosticReport.chain`), so that the difference reads as the
  difference it is.

## Asking

```kotlin
val doctor = MediaSourceDoctor.Builder(context)
    .setCache(cache)              // the cache this app's players are built with, if any
    .setResilience(resilience)    // and the credential they carry, if any
    .build()

val report = doctor.examine(request)   // the same MediaRequest a player adopts
report.findings.forEach { finding ->
    log("${finding.pathology.id} ${finding.severity} ${finding.magnitude}")
    log("  ${finding.specCitation} — ${finding.cause}")
}
```

`examine` blocks the calling thread for as long as the fetch takes, so it is called off the main
thread like any other network read. A doctor holds no thread, registers nothing with the platform
and keeps nothing open between calls, so there is nothing to release.

**An empty report is an answer, not an absence.** It means nothing the doctor can name is wrong with
this manifest — and the list of what it can name is exactly this document.

## Reading a report

Each finding has four parts.

| Part | What it is |
| --- | --- |
| `pathology.id` | the stable name, protocol first: `hls-ladder-gap`, `dash-short-time-shift-buffer-depth` |
| `severity` | what *this instance* costs playback: `ADVISORY`, `DEGRADED` or `BLOCKING` |
| `magnitude` | the value the defect was found at, in words — `adjacent rungs 48× apart, 128 kbps to 6144 kbps` — or absent where the defect is present-or-absent |
| `specCitation`, `cause` | the clause the stream departs from, and the misconfiguration that produces it in the field |

**Severity is per finding, not per defect**, because for a defect with a magnitude the severity is a
reading of that magnitude: the same ladder gap is advisory at one step and degraded at another.

- `ADVISORY` — the stream plays correctly and something about it is untidy or fragile.
- `DEGRADED` — the stream plays and something measurable is worse for the defect: a start that takes
  longer, a rung that cannot be chosen, a decision made on a number that is not true.
- `BLOCKING` — no player of this stream can play it as its publisher intended.

Two things a report does *not* promise. **Findings are not sorted by severity** — they are in the
order the examination made them, because a report is read as a list of facts rather than as a
ranking. And there is **at most one finding per defect**: a ladder examined through ten renditions is
one ladder, so where several renditions carry the same defect what is reported is the worst of them.
A report says how bad a stream is, not how many places a defect was seen in.

## What is fetched

For a DASH stream, the MPD. For an HLS stream, the multivariant playlist **and every media playlist
it names** — variants and audio renditions alike — which is more playlists than a player fetches and
is deliberate: a player reads the playlist of the rendition it is playing, while a doctor is asked
once, off the playback path, about the whole stream, and a defect in a rung nobody started on is one
every viewer whose link climbs to it will meet. A ten-rung ladder therefore costs eleven playlists
and no media.

Only the **first entry** of `MediaRequest.sources` is examined. The rest are what the fallback
ladder's rung 4 would reach for, and a stream nothing has fallen back to has not been asked to serve
anything.

The parsers are the ones the engine would run over the same bytes, so the doctor and a player cannot
disagree about what a manifest *says* before they disagree about what it means. A protocol the doctor
has no rules for yet is still fetched whole — so that a manifest no player of this app can reach
never comes back looking like a healthy one — and read by nothing.

---

## The defects

Grouped as the vocabulary groups them: HLS, then DASH, then the two that name a defect of the
*fetch* rather than of a document. Every defect but those two has a matching entry in this library's
curated hostile-manifest corpus (`docs/testing.md`'s *The hostile manifest corpus*), and two tests
are what keep the three lists in step: `CorpusRegisterTest` scores the doctor against every corpus
entry — for misses and for false positives alike — and `DoctorDocumentTest` fails the build if a
defect the doctor can name has no section below carrying its citation.

### Where the numbers come from

Every threshold is **a published bound where a document states one, and a derivation where none
does**, and this document says which each is, because the source code does:

| Threshold | Value | Source |
| --- | --- | --- |
| A step between adjacent rungs that is not a gap | up to 2× | Apple Technical Note TN2224, "Adjacent bit rates should be a factor of 1.5 to 2 apart" — the one published number for a ladder's step, and nothing in it is specific to HLS, which is why DASH is held to it too |
| A gap wide enough to be `DEGRADED` | above 8× | **Derived.** A client just short of the upper rung uses about `1 / step` of its link; 8× is two whole rungs missing from a ladder built at the guidance's ceiling |
| Segment durations within tolerance | a spread up to 3× | DASH-IF Interoperability Points v3.0 §3.2.1, "±50% of the signaled segment duration" — the widest conforming pair is half nominal beside one-and-a-half, a factor of three. RFC 8216 names no tolerance at all |
| A spread wide enough to be `DEGRADED` | above 10× | **Derived.** An order of magnitude is where a cushion stops covering the playlist: a buffer holding ten short segments holds one long one |
| What an audio-only rung can carry | 576 kbps per channel | ISO/IEC 14496-3 — AAC's bit reservoir bounds a raw data block to 6144 bits per channel over 1024 samples, which at 96 kHz (the highest sampling frequency the index admits) is 576 kbps. The highest rate deliberately, so that conforming content is under it twice over |
| An overstatement grave enough to be `DEGRADED` | above 2× that ceiling | **Derived.** The ceiling already assumes the most generous authoring, so just past it is a packager's rounding; twice it is past every such reading at once |
| A token lifetime worth reporting | under 2× the content's duration | **Derived**, because a signed URL's lifetime is a CDN's setting and no document speaks to it: the content, and as long again for the pauses a viewer takes inside it |
| A live window too short to hold a playhead | core's own judgement | Asked rather than restated (rule 6) — see `dash-short-time-shift-buffer-depth` |

Each one is argued at the constant that carries it in the source, and each sits **above** what
conforming content produces rather than in the middle of the range: a doctor is scored on its false
positives as heavily as on its misses (rule 12), so a rule that flags healthy content fails this
library's build.

---

### HLS

#### `hls-ladder-gap`

- **Citation:** RFC 8216 §4.3.4.2
- **Severity:** `ADVISORY` above a 2× step, `DEGRADED` above 8×. Magnitude names the widest step and
  the two rungs that make it.

**What it means.** Two adjacent rungs of the ladder are further apart than any published guidance
puts them, so a client whose throughput falls just short of the upper rung can sustain only the lower
one and uses roughly `1 / step` of its link. Nothing in the RFC constrains the spacing, so such a
playlist is valid to the letter and unplayable in between: the selector has a rung it wastes and a
rung it cannot sustain.

**What causes it.** A ladder whose middle rungs were dropped to save encoding cost, or a profile
template that was only ever tested on wifi and on 3G.

**What to change.** Add the missing middle rungs. A step of 1.5× to 2× between neighbours is the
target; the report's magnitude names the pair to fill in.

#### `hls-overstated-bitrate`

- **Citation:** RFC 8216 §4.3.4.2
- **Severity:** `ADVISORY` past the codec's ceiling, `DEGRADED` past twice it. Magnitude names what
  the rung declares and what its format can carry.

**What it means.** A rung's `BANDWIDTH` is above what the format it declares beside it could produce.
`BANDWIDTH` "represents the peak segment bit rate", and no MUST requires it to be accurate, so the
playlist is valid and the number is false. Every ABR estimator compares its throughput estimate
against it, so an overstated rung is one a player refuses on a link that would carry it easily.

**Read from declarations only, and narrowly.** The doctor weighs no media, so it bounds only a rung
it can bound from the manifest: one whose codec string names **AAC** (`mp4a.40.<n>`) and no video,
and which declares no picture. **A rendition carrying video is not checked at all**, because a video
codec's rate depends on a resolution, a frame rate and a profile neither manifest need declare, and a
guess would be a false positive; an audio rung in any other codec is not checked either, because the
ceiling below is AAC's own. Where the manifest declares no channel count — which HLS often cannot —
the ceiling is taken at **stereo**, since assuming more would raise it on every undeclared rung and
let a real overstatement through.

**What causes it.** A packager that writes the encoder's configured *peak* rather than the measured
rate, or a ladder whose bitrates were copied from a different mezzanine.

**What to change.** Write the measured peak segment rate. If the declared number is right and the
codec string is wrong, fix the codec string — the finding is a disagreement between the two, and
either side can be the wrong one.

#### `hls-missing-codecs`

- **Citation:** RFC 8216 §4.3.4.2
- **Severity:** `DEGRADED`. No magnitude: the attribute is present or it is not.

**What it means.** At least one `EXT-X-STREAM-INF` carries no `CODECS`. The RFC says a variant
*SHOULD* include one, so the playlist is valid; what its absence costs is that the player cannot know
whether it can decode a rendition until it has fetched a segment of it, which turns a capability
check into a download and a fallback into a stall. One finding however many rungs are missing it,
because the defect is of the manifest.

**What causes it.** A hand-written or templated multivariant playlist — the `CODECS` string is the
one attribute a human cannot produce without reading RFC 6381, so it is the one left out.

**What to change.** Emit `CODECS` on every variant, from the written media rather than from the
transcoder's job description.

#### `hls-audio-group-codec-mismatch`

- **Citation:** RFC 8216 §4.3.4.1, §4.3.4.2
- **Severity:** `DEGRADED`. No magnitude: a declared codec is the one delivered or it is not.

**What it means.** An audio group is declared as a format the media its own playlist names cannot
carry. The comparison a reader expects — the group's declared codec against the variant's — cannot be
made, and that is part of the rule: `EXT-X-MEDIA` has no `CODECS` attribute, so a group's only codec
declaration *is* the referencing variant's, and two declarations of one thing never disagree. What
does disagree is the declaration and the **container the group's own segments are in**, read the way
the engine reads it, from the segment URI's extension. ADTS can signal four AAC object types and no
more (its `profile` field is two bits holding `audioObjectType - 1`, ISO/IEC 14496-3), so a group
declared HE-AAC (`mp4a.40.5`) or HE-AACv2 (`mp4a.40.29`) over `.aac` segments names a format those
segments cannot be. A player that filters renditions or configures its output from the declared
format does so for media it will not receive.

**What causes it.** An encoder ladder migrated from HE-AAC to AAC-LC without the manifest template
being updated — the audio group still advertises what last year's profile produced.

**What to change.** Regenerate the variant's `CODECS` string from the audio actually written, or
package the audio in a container that can carry the declared object type.

#### `hls-dangling-audio-group`

- **Citation:** RFC 8216 §4.3.4.2
- **Severity:** `BLOCKING`. No magnitude.

**What it means.** A variant's `AUDIO` attribute names a group the playlist does not carry. The RFC's
"value MUST match the value of the `GROUP-ID` attribute of an `EXT-X-MEDIA` tag elsewhere in the
Multivariant Playlist" makes this document genuinely **malformed**, and a conformant client is
entitled to reject the whole playlist. It is the doctor's to name rather than the engine's to fail on
only because Media3 parses it leniently and plays it — which is a property of that player, not of the
content. This is the one HLS defect here that is blocking because the document is wrong rather than
because a number is large.

One unresolved reference is forgiven where the playlist carries muxed audio: a rendition with no
`URI` is muxed into the variant's own stream and keeps no group id, so exactly one reference may be
that group's. A second cannot be.

**What causes it.** A packaging job whose audio rendition failed after the variant lines were
written, leaving the `AUDIO` reference behind with nothing to point at.

**What to change.** Publish the missing `EXT-X-MEDIA` group, or remove the `AUDIO` reference from the
variants. Re-run the packaging job rather than patching the playlist: a job that half-failed once
will do it again.

#### `hls-inconsistent-segment-durations`

- **Citation:** RFC 8216 §4.3.2.1, §4.3.3.1
- **Severity:** `ADVISORY` above a 3× spread, `DEGRADED` above 10×. Magnitude names the shortest and
  longest segment and the spread between them.

**What it means.** The shortest segment and the longest are further apart than segmentation with any
published tolerance produces. Nothing in the RFC requires segments to be of *similar* length, so a
ragged playlist tells the truth and is still hostile: a load control holds a **duration** of media,
so a playlist whose segments differ by a factor of *n* asks it for wildly different amounts of work
from one segment to the next, and a cushion sized for the short ones is *n* times too small for a
long one.

The comparison is shortest against longest rather than either against `EXT-X-TARGETDURATION`, because
§4.3.3.1 fixes the target as the longest `EXTINF` rounded up — grading a ragged playlist against its
target would grade the defect against itself.

**What causes it.** Segmentation driven by scene-change keyframes rather than by a fixed GOP, or an
ad-insertion pass that split one segment and left its neighbours alone.

**What to change.** Segment on a fixed GOP cadence, and keep an ad-insertion pass from re-splitting
one segment in isolation.

#### `hls-discontinuity-without-timeline`

- **Citation:** RFC 8216 §4.3.2.3
- **Severity:** `DEGRADED`. No magnitude: the metadata is present or absent, with nothing in between.

**What it means.** The playlist carries an `EXT-X-DISCONTINUITY` — correctly, since §4.3.2.3 requires
one where the timestamp sequence changes — and neither of the optional tags that would place the new
timeline beside it: `EXT-X-PROGRAM-DATE-TIME` (§4.3.2.6) or `EXT-X-DISCONTINUITY-SEQUENCE` (§4.3.3.3,
which in its absence "SHALL be considered to be 0"). A client then has the accumulated `EXTINF`
durations and nothing else, so a seek across the splice lands where the arithmetic says rather than
where the media is, and a live edge computed across it is placed by accumulation alone.

An absent `EXT-X-DISCONTINUITY-SEQUENCE` and one written as zero are the same document to a client,
so the doctor cannot tell them apart and does not try.

**What causes it.** Mid-roll ad insertion by a stitcher that marks the splice and emits neither a
discontinuity sequence nor a program date time — the common case for server-side ads.

**What to change.** Have the stitcher emit `EXT-X-PROGRAM-DATE-TIME` across the splice, or maintain
`EXT-X-DISCONTINUITY-SEQUENCE`.

#### `hls-cached-live-playlist`

- **Citation:** RFC 8216 §6.2.1, §6.3.4; RFC 9111 §5.2.2.1
- **Severity:** `BLOCKING`. Magnitude names how long the playlist is held against its own target
  duration, and what its segments were served with.

**What it means.** A live media playlist is served with a `Cache-Control` lifetime longer than the
bound within which the server must publish a new version of it — at least one new segment within 1.5
target durations (§6.2.1). A shared cache obeying RFC 9111 may then answer with a version of the
playlist that is already late, and **every actor conforms**: HTTP caching has nothing to say about
what a playlist means. The client reloads a playlist that cannot change and the stream appears to
freeze about thirty seconds in.

**The playlist is the wrong side, always.** A media segment is immutable once published, so caching
it for a long time is correct and caching it for none is merely wasteful; the live media playlist is
the one document in the stream that changes. The segment's own reading is printed as *evidence* and
nothing branches on it — three sentences are possible, and each sends a support engineer somewhere
different: the pair is backwards (one cache rule applied to a whole path), both are held (a lifetime
set once for everything under it), or the segment declined to say. This is the one rule that spends
the headers-only segment probe, and it spends it only once it already has the defect.

On-demand playlists are passed over: an `EXT-X-ENDLIST` says the document will not change again, so a
long `max-age` on one is correct.

**What causes it.** One CDN cache rule applied to the whole path. This project's own observation,
recorded in the corpus entry: a surprising share of "live stream freezes" tickets are this.

**What to change.** Give the live media playlist its own rule, with a lifetime no longer than its
target duration (or `no-cache` with revalidation), and leave the segments held for as long as you
like. Met from the other end, this defect is the `StaleLivePlaylistException` a SuperPlayer session
fails with — the same comparison, asked before playback instead of during it.

#### `hls-token-scoped-to-manifest`

- **Citation:** RFC 3986 §3.4, §5.3; RFC 9110 §15.5.4
- **Severity:** `BLOCKING`. Magnitude counts the segments that carry no credential.

**What it means.** The manifest was fetched from a signed URL and its segments are addressed
relatively, so they resolve against the base URI's **path** — and the query component, where the
credential is, is not part of it (§5.3). The media is therefore addressed with no credential at all,
and a CDN enforcing the signature refuses it with a 403. Nothing in the playlist is wrong: the defect
exists only in the relation between the URL the app was handed and the URLs that playlist resolves
to, which is why it needs a fetch rather than a parse.

**A playlist where even one segment is signed is passed over**, deliberately: a rendition half of
whose URIs carry a credential is a signing step that is running and partly wrong, which is a
different report from one that never reached the media.

**What causes it.** A signing step applied to the URL the app was handed and to nothing below it, so
the stream opens and then serves nothing.

**What to change.** Sign the whole path rather than the one URL — a signed prefix or a cookie-scoped
policy — so that a relatively-addressed segment is covered.

#### `hls-token-expiring-in-window`

- **Citation:** RFC 3986 §3.4; RFC 9110 §15.5.4
- **Severity:** `BLOCKING` where less token life remains than the content's own duration, `DEGRADED`
  where less than twice it. Magnitude names what is left of the token against the length of the
  content.

**What it means.** A signed URL states its expiry in the clear, so an intermediary can refuse an
expired request without asking the signer. Where that expiry falls inside the content it was minted
for, the URL is valid when playback starts and refused part-way through — a failure **no rung of the
fallback ladder can repair**, because every source of one piece of content is signed by the same
service and dies at the same instant.

Only the `expires` query parameter is read, in exactly that lower-case spelling, and only its
presence and its value: nothing is verified and no CDN's scheme is reproduced. A URL signed under any
other spelling, or whose expiry is inside an opaque blob, is invisible to this rule and to
`hls-token-scoped-to-manifest`, and is reported as nothing rather than as healthy.

Live playlists are passed over, and that is the rule rather than a gap: a live stream has no end to
cover, so every finite token expires inside it.

**How much life is left is read against the device's clock**, unlike the DASH clock rule below, and
it has to be: an expiry is a fact about the world rather than about the document, and there is no
second party to hold it against. So a doctor run on a device whose clock is wrong can report this
defect on a healthy URL, or miss it on a doomed one.

**What causes it.** A signing service whose lifetime was set from how long a *request* takes rather
than from how long a *viewing* lasts.

**What to change.** Mint tokens with a lifetime of at least twice the content's duration — the
content, and as long again for the pauses a viewer takes inside it.

#### `hls-cors-refuses-credentials`

- **Citation:** WHATWG Fetch §3.3.5, §4.10
- **Severity:** `BLOCKING`. No magnitude: a configuration refuses the request or it does not.

**What it means.** A response carries `Access-Control-Allow-Origin: *` **and**
`Access-Control-Allow-Credentials: true`. Where a request's credentials mode is "include", §4.10's
CORS check fails unless the allowed origin is the request's own, whatever the credentials header
says, so the pair is a refusal by construction. It is the one CORS configuration that can be called
wrong without knowing which origin is asking — and the one defect here a native player never notices,
since one CDN configuration serves an app and a browser alike. On a stream that plays perfectly on
the device the report was taken from, this is the finding that matters most.

**A response that says nothing about CORS is not a misconfiguration**, and the rule is written that
way deliberately: on this project's reading, an origin serving native players commonly emits no CORS
headers at all and is entirely correct, so flagging silence would be a false positive on most of the
streams a doctor ever sees.

**It reads the manifest responses and not the media's**, which is a named gap: a manifest path and a
media path really can be configured differently, so a stream whose manifests are clean and whose
segments are not is reported as clean.

**What causes it.** An origin configured to "allow everyone".

**What to change.** Echo the requesting origin in `Access-Control-Allow-Origin` (with `Vary: Origin`)
where credentials are allowed, or stop advertising credentials.

---

### DASH

#### `dash-ladder-gap`

- **Citation:** ISO/IEC 23009-1 §5.3.5.2; DASH-IF IOP §3.2.4
- **Severity:** as `hls-ladder-gap`, over the same threshold.

**What it means and what to change** are its HLS twin's, exactly: `@bandwidth` is required per
Representation and nothing constrains the spacing between two of them, and DASH-IF's ladder guidance
is a recommendation. The rule is one rule over one threshold for both protocols, because a packager
writes both manifests from one ladder definition and a doctor that graded them differently would be
reporting on its own two minds. Compared **per `AdaptationSet`**, since that is DASH's unit of
adaptation: a step between an audio set's rung and a video set's is not a step any client takes.

**What causes it.** The same dropped middle rungs as its HLS counterpart, usually from the same
ladder definition.

#### `dash-overstated-bitrate`

- **Citation:** ISO/IEC 23009-1 §5.3.5.2
- **Severity:** as `hls-overstated-bitrate`, over the same ceiling and the same narrow reading
  (audio-only rungs; video is never bounded).

**What it means.** `@bandwidth` is defined against a hypothetical constant-rate channel that delivers
each segment in time, so a value above the real rate still satisfies it. The cost is the HLS twin's:
every bandwidth-based selection is made against a number above the truth.

**What causes it.** The encoder's configured peak written through to `@bandwidth`, unchanged, by a
packager that never measured a segment.

#### `dash-missing-codecs`

- **Citation:** ISO/IEC 23009-1 §5.3.7.2; DASH-IF IOP §3.2.4
- **Severity:** `DEGRADED`. No magnitude.

**What it means.** A Representation declares no `@codecs`. The attribute is an optional common
attribute, so the MPD validates; DASH-IF requires it precisely because without it the player has to
fetch and sniff the initialization segment before it can decide whether it can play the
Representation at all.

**What causes it.** An MPD assembled from a transcoder's job description rather than from the written
media — the job knows the bitrate and not the codec string.

**What to change.** Emit `@codecs` per Representation.

#### `dash-mid-stream-ladder-change`

- **Citation:** ISO/IEC 23009-1 §5.3.2, §5.3.9.2
- **Severity:** graded by the same pair of thresholds as a static ladder gap: `ADVISORY` above 2×,
  `DEGRADED` above 8×. Magnitude names how far the ladder moves at the boundary.

**What it means.** A Period offers a ladder its neighbour does not, so a client rebuilds its track
selection at the boundary and lands wherever the new rungs are. A Period is DASH's unit across which
the set of Representations may change, so this is entirely conforming and no parser will object.

**A Period boundary is not the defect and is not flagged.** Multi-period content is ordinary DASH,
and a manifest whose Periods offer the same rungs is reported as nothing at all. What is graded is
how far the re-selection can *jump*: for every rate one Period has and its neighbour does not, how
many times the nearest rate on the other side of the boundary it is — **within one track type**,
never across two, since a client replaces a video rendition with a video rendition. A type the
neighbouring Period does not carry at all is passed over rather than read as an infinite move.

**What causes it.** A mid-roll ad break, or an encoder restarted mid-event onto a different profile —
the second Period is whatever was running when it came back.

**What to change.** Publish every Period from one ladder definition, so that an ad break or a
restarted encoder offers the rungs the main content does.

#### `dash-availability-start-time-skew`

- **Citation:** ISO/IEC 23009-1 §5.3.1.2, §5.3.9.5.3
- **Severity:** `BLOCKING`, ungraded. Magnitude names how far ahead of the manifest's own clock the
  anchor sits; only the forward direction is ever reported, for the reason below.

**What it means.** A dynamic MPD's `@availabilityStartTime` is at or after the time the same MPD says
it is. The anchor is what every segment's availability is computed from, and nothing requires it to
agree with the clock, so such an MPD conforms — and says, correctly, that nothing is available yet. A
player of it **sits at a negative position with no error at all**, which is why this is the one corpus
entry the library's own fallback ladder is recorded as unable to recover from: nothing fails to load,
so no rung is ever offered. Naming it is all the doctor claims.

**What it is measured against, and what cannot be seen.** Against the MPD's **own** notion of now —
its `UTCTiming` where it carries a direct one (§5.8.4.11), and its `@publishTime` otherwise — and
never against the device's clock, which on a phone set wrong would report every live stream as
defective. The cost of that choice is a real limit, stated rather than papered over: **a skew smaller
than the time the stream has been on air is invisible here**, because such an MPD is character for
character the MPD of a healthy stream that started that much later. A miss, deliberately taken.

**What causes it.** A packager whose own clock or timezone offset is wrong: the MPD publishes a
correct `UTCTiming` and an `@availabilityStartTime` ahead of the stream's real start.

**What to change.** Fix the packager's clock or its timezone offset, and set
`@availabilityStartTime` to the instant the stream really began.

#### `dash-short-time-shift-buffer-depth`

- **Citation:** ISO/IEC 23009-1 §5.3.1.2
- **Severity:** `BLOCKING`, and **ungraded on purpose**. Magnitude names the declared window against
  the time a segment takes to become available.

**What it means.** `@timeShiftBufferDepth` is the guaranteed availability window for any segment, and
any positive duration conforms; a segment is available only once it is complete (§5.3.9.5.3). A
window no deeper than that lag therefore has no position a playhead can sit at, and no player can
start.

**The comparison is the library's own, at playback time, asked rather than restated** (ADR-0015
rule 6): it is core's `LiveWindowDepthCheck`, the same judgement a player of this stream ends a
session on with a `LiveWindowTooShortException`. So the doctor and a player cannot disagree about
whether a window is too short — they differ only in that one reports it before a session and the
other ends one. It is ungraded for the same reason: core's judgement is a yes or a no, and calling a
window *slightly* too short advisory would be holding the same manifest to a second threshold.

**What causes it.** A packager configured for the lowest possible latency, or a value in seconds
written where the packager wanted minutes.

**What to change.** Declare a window several segments deep — deeper than the time a segment takes to
become available, with room for a playhead.

#### `dash-missing-time-shift-buffer-depth`

- **Citation:** ISO/IEC 23009-1 §5.3.1.2
- **Severity:** `DEGRADED`. No magnitude: an absent window has no size.

**What it means.** A dynamic MPD carries no `@timeShiftBufferDepth` at all, and in its absence the
window is **infinite**: the MPD promises that every segment ever published stays available forever.
Valid, and untrue of every live origin — so the window a client plans its seeks and its recovery
against is one the CDN will not keep, and a seek backwards fetches a 404 rather than media. Playback
from the live edge is unaffected, which is what most of the session is, so this is degraded rather
than blocking.

Reported separately from the rule above rather than folded into it, because an unlimited window
cannot be *too short* and a packager who made these two mistakes would otherwise be sent to change
the wrong line.

**What causes it.** A live MPD produced by a VOD packaging template, which has no reason to emit the
attribute at all.

**What to change.** Emit `@timeShiftBufferDepth`, set to the window the origin really keeps.

---

### The two defects of the fetch

These two name something that went wrong with the transfer rather than with a document, so no corpus
of streams can carry them — every entry there serves its media perfectly. They exist because "the
doctor threw" is the least useful thing a support ticket can say (ADR-0015 rule 7).

#### `manifest-unreachable`

- **Citation:** RFC 9110 §15
- **Severity:** `BLOCKING`. Magnitude is `HTTP <status>` where a server answered with one, and absent
  where none did.

**What it means.** The manifest could not be fetched over the chain this app's players load through:
an origin or an edge that refused it, a credential it would not accept, or a host that did not
answer. On this project's own experience the commonest report of all, and the status is why it is
worth two words — "refused with 403" and "no host answered" send a support engineer to two different
teams.

The status is the only part of a refusal the report carries. **No message**, because the message of
most such exceptions carries the failing URL and the token in its query.

**What to change.** Depends on the status, and on the chain the report says it travelled: a 403 on a
doctor built with the app's credential is the CDN's policy, and the same 403 on one built without it
may be the app's.

#### `manifest-unreadable`

- **Citation:** RFC 8216 §4, ISO/IEC 23009-1 §5.3
- **Severity:** `BLOCKING`. No magnitude: there is nothing to play and nothing to grade.

**What it means.** The bytes arrived and are not a document either grammar admits — read off the
*engine's own parser* rejecting them, so what the doctor calls unreadable is exactly what the player
would have failed on. Told apart from the entry above because "nothing came back" and "something came
back that is not a manifest" send a support engineer to two different teams.

**What causes it.** A truncated or half-written document, an error or consent page served with a 200,
or a packager that emitted something else entirely.

**What to change.** Usually nothing about the manifest: look at what the edge is really serving on
that path, and at whether an error page is being returned with a success status.

---

## Reading a postmortem

The same call, handed what a session ended on, is the postmortem — one API and one report type
(ADR-0015 rule 8):

```kotlin
override fun onPlayerError(error: PlaybackException) {
    val report = doctor.examine(request, player.classify(error))
}
```

`report.classification` is then the `SuperPlayerError` that session ended on, **read and never
re-derived**, beside the findings the same request answers as a preflight. All four combinations are
reachable and each says something different:

| Findings | Classification | What it means |
| --- | --- | --- |
| yes | yes | a named defect of the stream, beside the class of failure it ended as |
| no | yes | the stream is fine as far as the doctor can see; the failure was the network's or the device's |
| yes | no | the stream is misconfigured and played to the end anyway — the defect costs quality, not the session |
| no | no | a preflight of a healthy stream, or a session that never failed |

**Neither vocabulary is expressed in the other, and disagreement is the information.** A
`classification` of null on a real failure is also an ordinary answer: it is what a player built
without `superplayer-resilience` reports, since nothing classified the failure.

## What the doctor cannot see

Stated rather than left to be discovered:

- **Anything that needs media weighed.** No segment is downloaded, so a rung whose real rate differs
  from its declaration is invisible unless the declaration contradicts its own codec string, and a
  video rung's rate is never bounded at all.
- **Delivery defects on DASH.** The four delivery rules are HLS-named, because a defect's id is the
  corpus entry it is scored against and all four of those entries are HLS. Nothing in the rules is
  protocol-specific — a token lives in a URI and an allowed origin in a header — so a DASH stream with
  a CORS misconfiguration is a gap this document names rather than covers.
- **CORS on the media path.** Only manifest responses are read; see `hls-cors-refuses-credentials`.
- **A signing scheme spelled differently.** Only a lower-case `expires` query parameter is read.
- **A token's remaining life on a device whose clock is wrong.** The two token rules read the
  device's clock; see `hls-token-expiring-in-window`.
- **An audio rung in a codec other than AAC.** See `hls-overstated-bitrate`.
- **A clock skew smaller than the time a live stream has been on air.** See
  `dash-availability-start-time-skew`.
- **Anything about the sources after the first.** Only the first entry of `MediaRequest.sources` is
  examined.

## Watching a session instead of examining a stream

The doctor answers a question about a *stream*. Two other artifacts answer questions about a
*session*:

- **The debug HUD** — `DebugHud(player, telemetry)`, an overlay the app places over its own surface
  while the thing is playing in front of a developer, and which renders nothing unless the
  application is debuggable. Its rows and what each can and cannot show are `DebugHud`'s own
  documentation and `docs/testing.md`'s *The debug HUD*.
- **The session bundle** — one session as one text artifact a bug report can carry.
  [`docs/session-bundle.md`](session-bundle.md) is its format and
  [`docs/reading-a-session-bundle.md`](reading-a-session-bundle.md) is how to read one.

## Further reading

- [ADR-0015](adr/0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md)
  — why the doctor fetches over the player's own chain, why a pathology is not a failure, and what
  the module may reach.
- [`docs/testing.md`](testing.md) — *The hostile manifest corpus* and the corpus register, which is
  how every defect above is scored for both misses and false positives.
- [`docs/modules.md`](modules.md) — where `superplayer-diagnostics` sits and what it may depend on.
