# CMCD on iOS — the spike's finding

**Status:** finding, 2026-10-11 (#413). **Decision: CMCD ports**, through the SDK's own emitter, and
the schema's `sid` join holds on iOS 18 and tvOS 18 and later. The implementation is #446.

[ADR-0020](adr/0020-compose-on-avfoundations-avplayer-and-bring-no-other-engine.md) rule 7 made CMCD
on Apple platforms conditional on two questions, to be answered against the SDK before a line of CMCD
was written: does AVFoundation emit CTA-5004 itself, and can the session id that
[`telemetry-schema.md`](telemetry-schema.md)'s `sid` join promises reach the request. A negative
answer to both would have closed CMCD on iOS. The answer to the first is **yes**, and to the second
**yes, by a different route than the plan named**: not `AVURLAssetPrimarySessionIdentifierKey`,
which reaches no CMCD key at all, but the asset's own `httpSessionIdentifier`, which *is* the `sid`
and can be read before the first request leaves.

## What the SDK says

Read in Xcode 27's iOS simulator SDK, `iPhoneSimulator27.1.sdk`, under
`System/Library/Frameworks/AVFoundation.framework/Headers/`. A search of the whole directory for
`CMCD`, `CommonMediaClientData`, `PrimarySessionIdentifier`, `httpSessionIdentifier` and
`sessionIdentifier` found these three and nothing else; the Swift overlay
(`usr/lib/swift/AVFoundation.swiftmodule`) adds no CMCD symbol of its own.

| API | Header | Available | What it says |
| --- | --- | --- | --- |
| `AVAssetResourceLoader.sendsCommonMediaClientDataAsHTTPHeaders` | `AVAssetResourceLoader.h:418–428`, category `AVAssetResourceLoaderCommonMediaClientDataSupport` | iOS 18, tvOS 18 | "Enables Common Media Client Data to be attached as HTTP request headers." Defaults to `NO`. |
| `AVURLAsset.httpSessionIdentifier` | `AVAsset.h:691–695` | iOS 16, tvOS 16 | A read-only `NSUUID` "automatically included in any HTTP request issued on behalf of this asset in the HTTP header field `X-Playback-Session-Id`". Copies of an asset vend an equal one. |
| `AVURLAssetPrimarySessionIdentifierKey` | `AVAsset.h:616–620` | iOS 16, tvOS 16 | A UUID the app chooses, appended as the query parameter `_HLS_primary_id` "to selected HTTP requests"; documented for matching interstitial assets to the primary item's (`AVPlayerInterstitialEventController.h:127`, `:244`). |

The headers state no query-parameter mode, no per-key control, no `cid`, and no way to *set* the
session identifier: the property is read-only, and the one key that takes an app-chosen UUID is the
interstitial one. They do not say what `sid` will be — that is what the observation below is for.

## What the origin saw

`CmcdFindingTests`, in `ios/Tests/SuperPlayerTestkitTests/`, plays a stock `AVPlayer` from the
harness's loopback origin ([ADR-0021](adr/0021-test-ios-against-a-loopback-origin-and-run-its-checks-in-one-script.md)
rule 2) and asserts on the request line and headers the origin recorded, with and without each
candidate. It is kept rather than run once, so that an SDK which changes any of what follows fails
it with a message pointing here. Observed on the iOS 27.1 simulator runtime, Xcode 27, against the
synthetic on-demand stream (three 2 s AAC segments) and the live one:

| Configuration | `CMCD-*` headers | `sid` | Query string |
| --- | --- | --- | --- |
| Asset left alone | none, on any request | — | none |
| `sendsCommonMediaClientDataAsHTTPHeaders = true` | `CMCD-Object`, `CMCD-Request`, `CMCD-Session` on **every** playlist and segment request | `"<httpSessionIdentifier>"` on every request | none |
| The flag, plus `AVURLAssetPrimarySessionIdentifierKey: <chosen UUID>` | as above | still `"<httpSessionIdentifier>"`; the chosen UUID appears in no CMCD key | `_HLS_primary_id=<chosen UUID>` on the multivariant playlist only |
| The flag, live stream | as above | one value across every request | none |

`X-Playback-Session-Id: <httpSessionIdentifier>` is on every request in every configuration,
CMCD or not. The `sid` is that same UUID string, uppercase, quoted as CTA-5004 §3.1 requires.

One segment request of the on-demand stream, as the origin received it:

```
CMCD-Object:  d=2000,ot=a
CMCD-Request: bl=2000,mtp=1700,nor="segment2.aac",su
CMCD-Session: pr=0,sf=h,sid="161F5254-727A-4215-AC83-DE7B6182221D",st=v
X-Playback-Session-Id: 161F5254-727A-4215-AC83-DE7B6182221D
```

The keys seen across the runs were `ot`, `d`, `bl`, `mtp`, `nor`, `su`, `pr`, `sf`, `sid` and `st`
(`v` on demand, `l` live; `ot=m` on playlists, `ot=a` on these audio-only segments). No `CMCD-Status`
header, no `v` key (so version 1, CTA-5004 §3.1's default), no `cid`, and no `br`. The last is a
reading of *this* content and not a claim about the SDK: the synthetic stream is a single audio
rendition, and whether `br` appears on a video ladder is #446's to observe.

## What that means for the join

On Android the session id is **minted** by core and handed to both seams — `MeasurementSession` to
telemetry and `CmcdBinding.kt` to Media3's CMCD configuration — so that they cannot drift. On iOS the
SDK fills `sid` itself and cannot be told what to put there, so the direction reverses: core **reads**
`asset.httpSessionIdentifier.uuidString` from the `AVURLAsset` it built and uses that as the telemetry
`sessionId`. The two seams still cannot drift, because there is still one string and one place it
comes from — the asset rather than a `UUID()` call. Three consequences, each carried into #446:

- **One asset per session.** Copies of an asset vend an equal identifier, so an asset reused across
  two adoptions would put two sessions under one `sid`. Core builds a fresh `AVURLAsset` per
  `MediaRequest` adopted.
- **A fallback to the next source is the open question.** Android keeps a rung-4 fallback one session
  with one `sid`; on iOS the next source is a new asset with a new identifier. Whether that is one
  session reported under the first id (and the CDN then sees a second `sid` for it) or two is the rung
  4 ticket's (#420) to decide and the schema's to state.
- **Below iOS 18 and tvOS 18 there is no CMCD**, and nothing is refused: CMCD is not a call a consumer
  makes, so ADR-0020 rule 9's second shape applies — the measurement is coarser below 18 and the
  schema says which derivation applies where. The same string still reaches the CDN as
  `X-Playback-Session-Id`, which the header documents from iOS 16, so a CDN that logs that header can
  join a session from an iOS 17 device too. That is a fact about the header's documentation; this
  spike observed only the 27.1 runtime.

What does not port: `CmcdMode.QUERY_PARAMETER`, since the SDK offers headers alone (#446 refuses it
typed, rule 9's first shape); the `cid` key, so a per-title question asked of the CDN's log alone
needs the join on iOS; and any per-key or per-profile choice beyond on and off, which Android does not
exercise either — every row of `StaticCmcdPolicy` is `REQUEST_HEADERS`.

## What the spike could not see

- **Any runtime but iOS 27.1 on the simulator.** It is the only iOS runtime installed on the host the
  spike ran on; the macOS CI job runs the test on the newest runtime its image carries. iOS 18's behaviour is inferred from the header's availability, not observed, and a
  device was not used.
- **tvOS.** Built, not tested, as every iOS test is today (`ios/CLAUDE.md`); the header declares the
  same availability.
- **A download.** `AVAssetDownloadURLSession` loads through its own task, not an `AVPlayer`; whether
  the flag reaches it is outside this question, and Android sends no CMCD from a download either
  (ADR-0013 rule 6: no CMCD on the download chain).
- **What a real CDN logs.** As on Android, where `sid` lands is the CDN's business.

No custom-scheme resource loader was tried, and none will be: ADR-0020 rule 6 forbids it, and a
positive finding makes the question moot.
