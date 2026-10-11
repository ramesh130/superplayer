// Copyright 2026 The SuperPlayer Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import AVFoundation

/// What AVFoundation leaves behind when an item fails, handed to the ladder as one value: the error,
/// the error log's last entry — the one place AVFoundation names *which* request failed — and the
/// asset's own URL, the multivariant playlist. Core gathers it and decides nothing from it (ADR-0011
/// rule 1); `SuperPlayerResilience` is what reads it.
package struct FailureEvidence {
    package let error: NSError
    package let loggedFailure: AVPlayerItemErrorLogEvent?
    package let playlistURL: URL?

    package init(error: NSError, loggedFailure: AVPlayerItemErrorLogEvent?, playlistURL: URL?) {
        self.error = error
        self.loggedFailure = loggedFailure
        self.playlistURL = playlistURL
    }
}

/// The rungs core performs and the error it delivers, as the one object core asks — the Kotlin
/// `PlayerStateRungs`, with the credential repair beside rungs 4 and 5 (ADR-0011 rule 13's addendum,
/// and `docs/ios-plan.md` §3.5).
///
/// **Why three operations on the player's state, where Android has two.** Rungs 4 and 5 are the
/// Kotlin pair: the next entry of ``MediaRequest/sources``, and the player rebuilt where it stood.
/// The credential repair is Android's `TokenRefreshLayer`, which repairs a refused 401 or 403 inside
/// the transfer that met it; `AVPlayer` has no transfer for anything to sit inside, and an
/// `AVURLAsset`'s headers are fixed when it is made (ADR-0020 rule 6). So on Apple platforms the
/// repair is rung 4's *mechanism* — a re-adoption at the held position — under rule 12's *rule*: asked
/// first, of the app's `HeaderProvider`, before any rung is climbed, and declined by a provider with
/// nothing newer. Its cost is a fetch of every playlist again, which a repair inside the transfer
/// never paid; the facade's `reopen` says where.
///
/// **Which direction "only when the ladder asks" runs** is the Kotlin one: core reads no error code
/// and decides no class. What core contributes is the half the ladder cannot see — whether there is a
/// next source, how often the player was rebuilt at the position it is stuck at, whether what plays
/// is still a request it can re-adopt — and it asks only once its own half holds, so a `true` is a
/// rung performed and is what the ladder records.
///
/// One per player, made by ``FailureClassifying/playerStateRungs()``, and on the main actor: every
/// question is asked where the failure is heard and the item replaced.
@MainActor
package protocol PlayerStateRungs: AnyObject, Sendable {

    /// The headers every asset this player builds carries: empty until a credential was refreshed,
    /// then what the app's provider last returned, for every adoption after it — a token is the
    /// player's, not one item's, as the Kotlin `HeaderProvider`'s contract says.
    var requestHeaders: [String: String] { get }

    /// Whether `evidence` is a refused credential the app's provider may be asked about: a 401 or a
    /// 403, on a player whose resilience was given a provider, for a request not already repaired
    /// once — the Kotlin "asked once more", so a fresh credential refused again climbs on.
    func refusesCredential(_ evidence: FailureEvidence) -> Bool

    /// Asks the provider for a credential to replace the refused one, answering whether it gave a
    /// different one — which is then ``requestHeaders``. Returning what was sent, or throwing,
    /// declines, and the failure goes on up the ladder.
    func refreshCredential(_ evidence: FailureEvidence) async -> Bool

    /// Whether `evidence` may climb to rung 4, the next source — the class's ceiling, and the Kotlin
    /// `NextSource`'s refusal of a decoder that stopped. Whether there *is* a next source is core's.
    func opensNextSource(_ evidence: FailureEvidence) -> Bool

    /// Whether `evidence` is one rung 5 — the player rebuilt where it stood — is the remedy for.
    /// How often it has already been rebuilt there is core's, ``SuperPlayer/maxDecoderRecreations``.
    func recreatesPlayer(_ evidence: FailureEvidence) -> Bool

    /// What `evidence` is, at `positionMs`, with the rungs this player climbed for its content: rung
    /// 6's typed error where nothing below took the failure on, and the class telemetry reports for
    /// one a rung repaired (ADR-0011 rule 3).
    func typedError(for evidence: FailureEvidence, positionMs: Int64?) -> SuperPlayerError

    /// The content changed: the rungs climbed for what came before are not this content's.
    func forgetClimb()
}

/// The undocumented `AVURLAsset` option that sets HTTP headers on every request the asset issues —
/// its multivariant playlist, its media playlists and its segments.
///
/// **It is not in the SDK.** `AVAsset.h` declares no header option: the documented keys are the
/// user agent, cookies, the attribution and the session identifiers, and none carries an
/// `Authorization` header. This string is what AVFoundation reads all the same, it is the one way to
/// put a credential on an HLS asset's requests without a resource-loader delegate — which ADR-0020
/// rule 6 forbids, as the proxy in-process — and `CredentialRepairPlaybackTests` asserts the header
/// reaching the loopback origin, so a runtime that stopped honouring it fails a test rather than a
/// session. It is passed only on a player whose provider has returned something, so a player with no
/// credential to send builds every asset exactly as before. A consumer who would rather not rely on
/// it signs the URL instead — a query AVFoundation carries unaided — and names no provider.
///
/// **There is no public source for it**, and that is the exception this declaration argues rather
/// than a citation it lacks. ADR-0020 rule 6's #420 addendum records the decision and what it
/// risks: a runtime that drops the option, which the test catches, and an app review that objects
/// to the string, which an app that names no provider never ships a request with.
let assetHTTPHeaderFieldsKey = "AVURLAssetHTTPHeaderFieldsKey"

extension SuperPlayer {

    /// Climbs the ladder for `item`'s failure: the credential repair, rung 4, rung 5, and rung 6
    /// where none of them took it on — each asked only once core's own half holds, in rule 7's order.
    ///
    /// The held position is read once, first, before any rung replaces anything (rule 9). A rung
    /// performed delivers nothing to ``errors``: the failure it repaired is withheld from the
    /// consumer, which is rule 10 and the Kotlin facade's one swallowed callback; telemetry still
    /// reports its class, through ``TelemetryCollector/contentReopened(after:)``.
    func climb(
        _ evidence: FailureEvidence, of item: AVPlayerItem, _ rungs: any PlayerStateRungs,
        askingForCredential: Bool = true
    ) {
        let heldMs = heldPositionMs(of: item)
        if askingForCredential, currentRequestIsPlaying, rungs.refusesCredential(evidence) {
            Task { [weak self] in
                let refreshed = await rungs.refreshCredential(evidence)
                // The provider may take its time; content replaced or a player released meanwhile
                // is not this failure's to repair.
                guard let self, !self.isReleased, item === self.avPlayer.currentItem else { return }
                if refreshed {
                    self.reopen(
                        sourceAt: self.sourceIndex, atMs: heldMs,
                        repairing: rungs.typedError(for: evidence, positionMs: heldMs))
                } else {
                    self.climb(evidence, of: item, rungs, askingForCredential: false)
                }
            }
            return
        }
        if hasNextSource, rungs.opensNextSource(evidence) {
            reopen(
                sourceAt: sourceIndex + 1, atMs: heldMs,
                repairing: rungs.typedError(for: evidence, positionMs: heldMs))
            return
        }
        if mayRecreatePlayer(at: heldMs), rungs.recreatesPlayer(evidence) {
            recreatedAtMs = heldMs
            recreations += 1
            reopen(
                sourceAt: sourceIndex, atMs: heldMs,
                repairing: rungs.typedError(for: evidence, positionMs: heldMs))
            return
        }
        let typed = rungs.typedError(for: evidence, positionMs: heldMs)
        decidedFailure = typed
        deliver(typed)
    }

    /// The media services under every player were reset — the system's own sign that each decoder
    /// and output this player held is gone — heard on a player configured with resilience.
    ///
    /// Handed to the ladder as the error AVFoundation fails an item with for the same event,
    /// `AVError.mediaServicesWereReset`, so the classifier — not core — decides it is rung 5's
    /// (`docs/ios-plan.md` §3.5: "exactly the case"). Only the item playing now is offered it, and
    /// only once per item, as an item's own failure is.
    ///
    /// A reset the ladder cannot rebuild for is delivered as rung 6 at once, though the item has not
    /// reported a failure of its own. That covers an item from a bare URL, which has no request to
    /// re-adopt, and a third reset at one position. The decoders beneath it are gone either way, and
    /// a consumer told now can act before a frozen picture tells the viewer.
    ///
    /// ref: https://developer.apple.com/documentation/avfaudio/avaudiosession/mediaserviceswereresetnotification
    func mediaServicesWereReset() {
        guard let item = avPlayer.currentItem, !failureDelivered, let rungs else { return }
        failureDelivered = true
        let error = NSError(
            domain: AVFoundationErrorDomain, code: AVError.Code.mediaServicesWereReset.rawValue)
        climb(
            FailureEvidence(
                error: error, loggedFailure: nil, playlistURL: (item.asset as? AVURLAsset)?.url),
            of: item, rungs, askingForCredential: false)
    }

    /// Plays `sources[index]` of the current request at `atMs`, as the same content and the same
    /// measurement session — the Kotlin re-adoption that is *not* `adopt` (ADR-0011 rule 10).
    ///
    /// **What is kept**: the identity (the item carries the request's metadata again), the request,
    /// the measurement session — no edge is signalled, so telemetry sees one viewing — and the
    /// player's climb. **What is new**: the `AVURLAsset` and the item, because neither can be told a
    /// new URL or new headers once made, which is the whole of why the repair costs what it costs —
    /// **every playlist is fetched again**, the multivariant one first, and the segment at the held
    /// position after them. Two consequences worth knowing: the new asset's `httpSessionIdentifier` is
    /// its own, so the CMCD `sid` and `X-Playback-Session-Id` the CDN sees change at a rung while the
    /// telemetry `sessionId` does not (`docs/telemetry-schema.md`, *On iOS and tvOS*); and the
    /// position is the one handed in rather than the request's ``MediaRequest/StartPosition``, and is
    /// remembered first, so ``MediaRequest/StartPosition/resumeFromLastKnown`` agrees at the instant
    /// of the rung (rule 9).
    ///
    /// The `AVPlayer` is kept for rung 5 as well, and that is a decision rather than an omission:
    /// ``avPlayer`` is the escape hatch a view already holds, and a rebuilt engine would orphan every
    /// `AVPlayerLayer` and `AVPlayerViewController` an app made from it. What a media services reset
    /// destroys is the decoders and outputs beneath the item, and an item built again asks for them
    /// again; whether a device ever needs the `AVPlayer` itself rebuilt is a device's question, which
    /// `docs/testing.md`'s *Rungs 4 and 5 on iOS* lists.
    func reopen(sourceAt index: Int, atMs: Int64, repairing failure: SuperPlayerError) {
        guard let request = currentRequest, request.sources.indices.contains(index) else {
            // Unreachable: every caller asked `currentRequestIsPlaying` or `hasNextSource` first.
            assertionFailure("a rung re-adopts only the request playing, at a source it has")
            return
        }
        rememberedPositions.remember(atMs, for: request.contentId)
        let item = freshItem(for: request, source: index)
        // Before the item is the player's, as `load` seeks, so the first segment fetched is the one
        // at the held position; zero tolerance, because the position is the viewer's.
        item.seek(
            to: CMTime(value: atMs, timescale: 1_000), toleranceBefore: .zero,
            toleranceAfter: .zero, completionHandler: nil)
        replaceCurrentItem(with: item, identified: true)
        currentRequest = request
        itemStartMs = atMs
        sourceIndex = index
        collector?.contentReopened(after: failure)
        // A failed item drops the player's rate to zero with no reason given (observed, #420); the
        // viewing resumes at the rate the viewer last asked for, and a paused one stays paused —
        // the Kotlin re-adoption keeps `playWhenReady` the same way, by not touching it.
        if requestedRate != 0 { avPlayer.rate = requestedRate }
    }

    /// Whether the current request has a source after the one playing, and is still what plays — a
    /// consumer who replaced the item through ``avPlayer`` has moved on without passing through here,
    /// the Kotlin `hasNextSource`'s guard.
    var hasNextSource: Bool {
        guard let request = currentRequest, currentRequestIsPlaying else { return false }
        return request.sources.indices.contains(sourceIndex + 1)
    }

    /// Core's half of rung 5: whether rebuilding at `heldMs` could be a remedy rather than the start
    /// of a loop — the Kotlin `mayRecreateDecoder`, its count starting over wherever playback
    /// advanced, so it bounds *futile* rebuilds only. A bare URL's item has no request to re-adopt.
    func mayRecreatePlayer(at heldMs: Int64) -> Bool {
        guard currentRequestIsPlaying else { return false }
        if let recreatedAtMs, heldMs > recreatedAtMs { recreations = 0 }
        return recreations < Self.maxDecoderRecreations
    }

    /// Whether what plays is still the current request's — the half of every re-adoption core can
    /// check, since a bare URL's item, or one a consumer set through ``avPlayer``, has no request to
    /// re-adopt. How often a credential is refreshed is the ladder's (one question per refused
    /// request), because which request was refused is the error log's reading.
    var currentRequestIsPlaying: Bool {
        guard let currentRequest else { return false }
        return currentContentId == currentRequest.contentId
    }

    /// Where `item` had reached: its time, or — where it reports none, an item that failed before it
    /// was ready — the position it was made to start at, which is where the viewer was.
    func heldPositionMs(of item: AVPlayerItem) -> Int64 {
        let time = item.currentTime()
        let reported = time.isNumeric ? Self.milliseconds(time) : 0
        return reported == 0 ? (itemStartMs ?? 0) : reported
    }

    /// Forgets core's half of the climb, at every adoption: the next content gets the whole ladder.
    func forgetClimb() {
        sourceIndex = 0
        recreatedAtMs = nil
        recreations = 0
        rungs?.forgetClimb()
    }
}
