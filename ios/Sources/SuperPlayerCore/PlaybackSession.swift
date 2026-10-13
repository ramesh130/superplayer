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
import MediaPlayer
import UIKit

/// A player published to the rest of the system: the lock screen, Control Centre, a headset's
/// buttons, AirPods, the Siri remote, and CarPlay's Now Playing screen.
///
/// ```swift
/// let session = PlaybackSession(player: player, resolver: catalog)
///
/// // ...and when the player's life ends, one call ends both:
/// session.release()
/// ```
///
/// The port of the Kotlin `PlaybackSession` (`docs/ios-plan.md` §3.1), whose three reasons are
/// this type's, said again in brief because a reader here has no copy of the Kotlin:
///
/// - **It is a choice, not a default** (ADR-0007 rule 1). A player alone publishes nothing — the
///   Now Playing dictionary and the remote command targets are untouched until a session is made —
///   because Now Playing is one per process and a feed of players has exactly one thing worth
///   publishing. Every observation the session makes is its own (`SessionSignals.swift`), so a
///   player nobody published pays nothing for this either.
/// - **One lifetime, not two.** ``release()`` ends the session and then the player, in that order;
///   ``setPlayer(_:)`` swaps the player behind a live session, so a change that needs a new player
///   does not tear the lock screen down.
/// - **Content identity survives the boundary.** What is published names the content by its
///   ``MediaRequest/contentId``, as `MPNowPlayingInfoPropertyExternalContentIdentifier`, and an id
///   that comes back from outside the app is resolved through the app's catalog by a
///   ``MediaRequestResolver`` and adopted as ``SuperPlayer/setMediaRequest(_:)`` would
///   (``setMediaRequest(forContentId:)``).
///
/// **What it publishes** is the request's title, subtitle and artwork, the item's duration (or that
/// it is live), the elapsed time and the rate; **what it answers** is play, pause, toggle, a seek
/// to a position, and a skip forward of ``skipForwardIntervalMs`` and back of
/// ``skipBackwardIntervalMs``. Each is applied to ``SuperPlayer/avPlayer``, where every other
/// transport control acts.
///
/// **A `RealtimePlayer` is published the same way** (#461), through `SuperPlayerRealtime`'s own
/// initializer, since core cannot name that type (ADR-0020 rule 11): its stream is published as
/// live, with no duration and no elapsed time, and it answers play, pause and toggle and **no seek
/// command** — a realtime timeline has no past to move to (ADR-0018 rule 5), and a scrubber or a
/// skip button that did nothing would be worse than none. ``player`` is nil on such a session.
///
/// **There is no service.** The Kotlin `PlaybackService` exists because Android ends a process
/// that plays in the background without a foreground service holding a notification. iOS has no
/// such thing: an app that declares the `audio` background mode keeps playing when backgrounded,
/// and the lock screen draws itself from what this session publishes (`docs/ios-plan.md` §3.1). The
/// background mode is the *app's* `UIBackgroundModes` entry, and so are CarPlay's entitlement and
/// its templates — what a CarPlay list shows and which id a row names — as the Android manifest
/// entries are the app's (ADR-0007 rule 4); this library declares nothing in anyone's `Info.plist`.
///
/// **One process has one Now Playing.** The session made most recently holds it; an earlier one
/// still alive is silent until released (`NowPlayingPlatform.swift` argues why that and not a trap).
///
/// It names one MediaPlayer type in no public signature: MediaPlayer is what it drives, behind the
/// boundary, as the Kotlin session's `MediaSession` is internal.
@MainActor
public final class PlaybackSession {

    /// How far the skip-forward command moves: Media3's `C.DEFAULT_SEEK_FORWARD_INCREMENT_MS`,
    /// which is what the Kotlin session's controllers seek by, since SuperPlayer sets no increment
    /// there — so a headset's button moves a viewer the same distance on both platforms.
    ///
    /// ref: Media3 `androidx.media3.common.C.DEFAULT_SEEK_FORWARD_INCREMENT_MS`
    nonisolated public static let skipForwardIntervalMs: Int64 = 15_000

    /// How far the skip-back command moves: Media3's `C.DEFAULT_SEEK_BACK_INCREMENT_MS`, for
    /// ``skipForwardIntervalMs``'s reason. Shorter than forward because a viewer skips back to catch
    /// a line they missed and forward past something they do not want.
    ///
    /// ref: Media3 `androidx.media3.common.C.DEFAULT_SEEK_BACK_INCREMENT_MS`
    nonisolated public static let skipBackwardIntervalMs: Int64 = 5_000

    /// The player this session publishes, or nil on a session publishing a `RealtimePlayer` —
    /// which `SuperPlayerRealtime` reads through its own property. Replaced by ``setPlayer(_:)``;
    /// released by ``release()``.
    public var player: SuperPlayer? { engine as? SuperPlayer }

    /// The engine published, of either player type (``PlaybackEngine``, ADR-0020 rule 11).
    package private(set) var engine: any PlaybackEngine

    private let resolver: (any MediaRequestResolver)?
    private let centre: NowPlayingCentre

    /// What ``NowPlayingCentre`` knows this session by; set once, as the session claims it.
    private var token: UInt64 = 0

    /// What ``engine`` registered for this session (``PlaybackEngine/observeNowPlaying(_:)``),
    /// while the session publishes it.
    private var observations: [AnyObject] = []

    /// The artwork fetched for the URL it names, and the fetch in flight, if any.
    private var artwork: (url: URL, image: UIImage)?
    private var artworkFetch: (url: URL, task: Task<Void, Never>)?

    /// Whether ``release()`` has run.
    private(set) var isReleased = false

    /// Publishes `player`, resolving content named from outside the app through `resolver`.
    ///
    /// With no resolver — the default — ``setMediaRequest(forContentId:)`` declines every id, which
    /// is the honest answer for an app that has not said it understands ids from outside itself.
    public convenience init(player: SuperPlayer, resolver: (any MediaRequestResolver)? = nil) {
        self.init(engine: player, resolver: resolver, centre: .system)
    }

    /// A session publishing to `centre`'s platform rather than the system's — the seam a test
    /// reads the dictionary and fires a command through (ADR-0021 rule 5's #403 addendum).
    package convenience init(
        player: SuperPlayer, resolver: (any MediaRequestResolver)? = nil, centre: NowPlayingCentre
    ) {
        self.init(engine: player, resolver: resolver, centre: centre)
    }

    /// A session publishing `engine`, of either player type — what `SuperPlayerRealtime`'s public
    /// initializer for a `RealtimePlayer` calls.
    package init(
        engine: any PlaybackEngine, resolver: (any MediaRequestResolver)? = nil,
        centre: NowPlayingCentre = .system
    ) {
        self.engine = engine
        self.resolver = resolver
        self.centre = centre
        self.token = centre.claim(self, commands: engine.remoteCommands)
        observe(engine)
        publish()
    }

    deinit {
        // A session dropped without `release()` still lets go of Now Playing, one main-actor turn
        // later — `deinit` is not isolated on the iOS 17 floor, as `LifecycleBinding`'s is not. The
        // player is not released: that is `release()`'s promise, and a `deinit` is no place to
        // keep it.
        Task { @MainActor [centre, token] in centre.relinquish(token) }
    }

    /// Swaps in a different player behind the live session, and releases the one that was there.
    ///
    /// For a change that needs a new player — a ``PlaybackProfile`` is fixed for a player's life,
    /// so a data-saver switch is one — without tearing the lock screen and every remote control
    /// down for a settings change. Take a ``PlaybackSnapshot`` from ``player`` and restore it into
    /// the incoming one *before* calling this: the outgoing player is released here.
    public func setPlayer(_ player: SuperPlayer) {
        setEngine(player)
    }

    /// Swaps in `engine`, of either player type, behind the live session — ``setPlayer(_:)``, and
    /// `SuperPlayerRealtime`'s for a `RealtimePlayer`. The buttons drawn follow the engine: a
    /// realtime stream swapped in behind a session withdraws the seek commands, and the reverse
    /// draws them again.
    package func setEngine(_ engine: any PlaybackEngine) {
        guard !isReleased, engine !== self.engine else { return }
        let outgoing = self.engine
        observations = []
        self.engine = engine
        centre.offer(engine.remoteCommands, from: token)
        observe(engine)
        publish()
        outgoing.release()
    }

    /// Plays the content `contentId` names, resolved through this session's
    /// ``MediaRequestResolver`` and handed to ``player`` exactly as
    /// ``SuperPlayer/setMediaRequest(_:)`` would — which is what makes content started from a car
    /// resume where the phone left it, when the resolver answers
    /// ``MediaRequest/StartPosition/resumeFromLastKnown``.
    ///
    /// What calls it is the app's code for whatever named the id: a CarPlay list row's handler, a
    /// Siri media intent, a universal link. On Android a controller's `setMediaItem` reaches the
    /// resolver through Media3's session callback; on iOS nothing outside the app can name content
    /// to a player, so the app's handler is where the id arrives and this is where it goes. Like
    /// ``SuperPlayer/setMediaRequest(_:)``, it does not start playback: a handler that means "play
    /// this" calls `player.avPlayer.play()` after.
    ///
    /// On a session publishing a `RealtimePlayer`, a request that player refuses — one that does not
    /// start at the live edge, or names a scheme none of its transports answers — is declined too,
    /// with the player left as it was.
    ///
    /// - Returns: the request adopted, or nil — and the player untouched — when the resolver
    ///   declined the id, there is no resolver, the player refused it, or the session is released.
    @discardableResult
    public func setMediaRequest(forContentId contentId: String) -> MediaRequest? {
        guard !isReleased, let request = resolver?.resolve(contentId: contentId),
            engine.adopt(resolved: request)
        else {
            return nil
        }
        return request
    }

    /// Ends the session and the player together: the session first, always.
    ///
    /// The Kotlin order, for an iOS form of the Kotlin reason. A released player has no item, so a
    /// session that outlived it would go on drawing the last content on the lock screen with live
    /// buttons that act on nothing — the stale lock screen every hand-rolled integration ships. So
    /// the dictionary is cleared and every command target removed while the player still holds its
    /// content, and only then is the player released.
    ///
    /// Idempotent, so two owners tidying up do not have to agree which of them wins.
    public func release() {
        guard !isReleased else { return }
        isReleased = true
        observations = []
        artworkFetch?.task.cancel()
        artworkFetch = nil
        centre.relinquish(token)
        engine.release()
    }

    /// The registrations live on this session, per signal — what the session register's test
    /// counts. Internal and reached with `@testable`, as `SuperPlayer.liveObservations` is.
    var liveObservations: [SessionSignal: Int] {
        observations.reduce(into: [:]) { counts, observation in
            guard let observation = observation as? SessionObservation else { return }
            counts[observation.signal, default: 0] += 1
        }
    }

    /// Carries out a remote command on ``engine``, answering whether it could be.
    func handle(_ event: RemoteCommandEvent) -> Bool {
        guard !isReleased else { return false }
        return engine.perform(event)
    }

    private func observe(_ engine: any PlaybackEngine) {
        observations = engine.observeNowPlaying { [weak self] in self?.publish() }
    }

    /// Publishes what ``engine`` is playing now, fetching its artwork first if it has some that
    /// has not been fetched.
    private func publish() {
        guard !isReleased else { return }
        let request = engine.nowPlayingRequest
        fetchArtwork(request?.artworkURL)
        let image = request?.artworkURL.flatMap { url in artwork?.url == url ? artwork?.image : nil
        }
        centre.publish(engine.nowPlayingInfo(artwork: image), from: token)
    }

    /// Fetches `url` unless it is already fetched or being fetched, and republishes once it has
    /// arrived — the Kotlin session's bitmap loader, which Media3 runs for `artworkUri`, because the
    /// Now Playing dictionary takes an image and never a URL.
    ///
    /// Fetched with `URLSession.shared`, which is the system's HTTP stack and the one `AVPlayer`
    /// uses too (ADR-0016 records that iOS takes no transport of a consumer's). A fetch that fails,
    /// or answers something that is not an image, publishes no artwork and is not retried: the
    /// lock screen shows the app's icon, which is what it shows for content with none.
    private func fetchArtwork(_ url: URL?) {
        guard let url, artwork?.url != url, artworkFetch?.url != url else { return }
        artworkFetch?.task.cancel()
        let task = Task { [weak self] in
            let data = try? await URLSession.shared.data(from: url).0
            guard !Task.isCancelled, let self else { return }
            self.artworkFetch = nil
            guard let image = data.flatMap(UIImage.init(data:)) else { return }
            self.artwork = (url, image)
            self.publish()
        }
        artworkFetch = (url, task)
    }
}

/// Turns a content id from outside the app back into a ``MediaRequest``.
///
/// The port of the Kotlin `MediaRequestResolver` (`docs/ios-plan.md` §3.1), and its argument: what
/// names content from outside the app — a CarPlay list row, a Siri request, the Now Playing item's
/// identifier coming back after a relaunch — has an id and nothing else, because an id is what
/// SuperPlayer publishes as the content's identity. Given it, return the request the app would have
/// made: the sources, what to display, and where to start, including
/// ``MediaRequest/StartPosition/resumeFromLastKnown`` — which is how content started from a car
/// resumes where the phone left it, a behaviour nobody implements by accident.
///
/// Returning nil declines, and is the honest answer for an id this app does not recognise. Called
/// on the main actor in the middle of a handler, so it must not block: an app whose catalog lives
/// behind the network resolves from what it already holds.
@MainActor
public protocol MediaRequestResolver {
    /// The request for `contentId`, or nil if this app does not recognise it.
    func resolve(contentId: String) -> MediaRequest?
}

extension SuperPlayer {

    /// The request the current item was made from, or nil when the item is not one the facade made
    /// from a request — an item handed to ``avPlayer`` directly may carry another request's
    /// identity, and then the request the facade remembers is not this item's.
    var currentRequestForCurrentItem: MediaRequest? {
        guard let request = currentRequest, request.contentId == currentContentId else {
            return nil
        }
        return request
    }
}

/// The Now Playing dictionary for what a player is playing, in MediaPlayer's keys.
package enum NowPlayingInfo {

    /// Nil with no item, since there is nothing to show; otherwise the item's duration, elapsed time
    /// and rates, and — for content a request named — its identity, title, subtitle and artwork.
    ///
    /// The subtitle is `MPMediaItemPropertyArtist`, because MediaPlayer has no subtitle key and the
    /// artist is the second line every Now Playing surface draws beneath the title — the Kotlin
    /// `MediaMetadata.subtitle`'s place in Android's media controls.
    ///
    /// ref: https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfocenter
    @MainActor
    static func of(_ player: AVPlayer, request: MediaRequest?, artwork: UIImage?)
        -> [String: Any]?
    {
        guard let item = player.currentItem else { return nil }
        var info: [String: Any] = [
            MPNowPlayingInfoPropertyPlaybackRate: Double(player.rate),
            MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(player.defaultRate),
        ]
        let elapsed = player.currentTime()
        if elapsed.isNumeric { info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed.seconds }
        // Indefinite is both "not ready" and "live"; only a ready item's indefinite duration says
        // live, since `AVPlayerItem.h` reports an unready item's as indefinite too.
        if item.duration.isNumeric {
            info[MPMediaItemPropertyPlaybackDuration] = item.duration.seconds
        } else if item.status == .readyToPlay && item.duration.isIndefinite {
            info[MPNowPlayingInfoPropertyIsLiveStream] = true
        }
        identify(&info, request: request, artwork: artwork)
        return info
    }

    /// The dictionary for a realtime stream (#461): nil with no request playing; otherwise live,
    /// at `rate` — one while playing, zero while not — and the request's identity, title, subtitle
    /// and artwork, as for any content.
    ///
    /// **No duration and no elapsed time**, deliberately: a live stream has neither, and an elapsed
    /// time is what the lock screen draws a scrubber from — one a viewer could drag on a timeline
    /// with no past.
    @MainActor
    package static func live(request: MediaRequest?, rate: Double, artwork: UIImage?)
        -> [String: Any]?
    {
        guard let request else { return nil }
        var info: [String: Any] = [
            MPNowPlayingInfoPropertyIsLiveStream: true,
            MPNowPlayingInfoPropertyPlaybackRate: rate,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
        ]
        identify(&info, request: request, artwork: artwork)
        return info
    }

    /// What every dictionary says about content a request named: its identity, title, subtitle and
    /// artwork.
    @MainActor
    private static func identify(
        _ info: inout [String: Any], request: MediaRequest?, artwork: UIImage?
    ) {
        guard let request else { return }
        info[MPNowPlayingInfoPropertyExternalContentIdentifier] = request.contentId
        if let title = request.title { info[MPMediaItemPropertyTitle] = title }
        if let subtitle = request.subtitle { info[MPMediaItemPropertyArtist] = subtitle }
        if let artwork { info[MPMediaItemPropertyArtwork] = Self.artwork(artwork) }
    }

    /// The artwork object, made outside the main actor on purpose: MediaPlayer calls its request
    /// handler on a queue of its own, and a closure written in a main-actor function would be
    /// inferred main-actor-isolated and trap there.
    nonisolated private static func artwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}
