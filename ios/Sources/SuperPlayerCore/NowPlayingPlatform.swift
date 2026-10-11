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

import Foundation
import MediaPlayer

// The one place a `PlaybackSession` becomes calls on the platform: the process's Now Playing
// dictionary and its remote command centre — the Kotlin `MediaSession` a `PlaybackSession` wraps,
// which on iOS and tvOS is not an object a session makes but two process singletons it borrows
// (`docs/ios-plan.md` §3.1, the session row). The lock screen, Control Centre, a Bluetooth headset's
// buttons, AirPods, CarPlay's Now Playing screen and the Siri remote all read these two and nothing
// else, which is why there is nothing else to publish to.
//
// They are behind one package-scoped protocol, `NowPlayingPlatform` (ADR-0021 rule 5's #403
// addendum), for two reasons. A test has to say "a player without a session left them untouched"
// and "a remote pause paused", and neither singleton can be reset between tests or made to fire a
// command: `MPRemoteCommand` has no public way to invoke a target, and the system only does so for
// a real button. And a feed of sessions in one process would fight over them, which
// `NowPlayingCentre` below settles in one place rather than in each session.

/// A command a remote control sends: the lock screen's buttons, a headset's, CarPlay's, the Siri
/// remote's. The six `docs/ios-plan.md` and #403 name — play, pause, toggle, seek and the two skips —
/// and no others, because a command with a target is a button the system draws, and a button that
/// does nothing for this player (next track, rating, language) is worse than none.
package enum RemoteCommand: CaseIterable, Hashable, Sendable {
    case play
    case pause
    case togglePlayPause
    /// The scrubber: a position the viewer dragged to.
    case changePlaybackPosition
    case skipForward
    case skipBackward
}

/// What a ``RemoteCommand`` asked for, read off the platform's event where it arrived.
package enum RemoteCommandEvent: Equatable, Sendable {
    case play
    case pause
    case togglePlayPause
    /// Seek to this position, in seconds from the content's start.
    case changePlaybackPosition(toSeconds: Double)
    /// Move this far from the current position, in seconds: negative is back.
    case skip(bySeconds: Double)
}

/// The platform half of a session: the Now Playing dictionary and the remote command targets.
///
/// One protocol for the feature, as ADR-0021 rule 5 asks, so the stub a test substitutes is a seam
/// read whole. The dictionary is the platform's own shape, `[String: Any]` keyed by MediaPlayer's
/// constants, so a stub records exactly what the system would have been handed.
@MainActor
package protocol NowPlayingPlatform: AnyObject {

    /// Replaces the process's Now Playing dictionary; nil clears it.
    func setNowPlayingInfo(_ info: [String: Any]?)

    /// Adds a target for `command`, which `handler` answers: true for handled, false for a command
    /// that could not be carried out (a seek with nothing to seek in).
    func addTarget(
        _ command: RemoteCommand,
        _ handler: @escaping @MainActor @Sendable (RemoteCommandEvent) -> Bool
    )

    /// Removes every target this platform added for `command`, and no one else's.
    func removeTargets(_ command: RemoteCommand)
}

/// The system's ``NowPlayingPlatform``: `MPNowPlayingInfoCenter.default()` and
/// `MPRemoteCommandCenter.shared()`.
@MainActor
final class SystemNowPlayingPlatform: NowPlayingPlatform {

    /// The tokens `addTarget(handler:)` returned, per command, which are what removing takes — so a
    /// target an app added to the same centre for a command of its own is never removed here.
    private var targets: [RemoteCommand: [Any]] = [:]

    func setNowPlayingInfo(_ info: [String: Any]?) {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    func addTarget(
        _ command: RemoteCommand,
        _ handler: @escaping @MainActor @Sendable (RemoteCommandEvent) -> Bool
    ) {
        let remote = Self.remote(command)
        if let skip = remote as? MPSkipIntervalCommand {
            // The interval the button is drawn with; the event carries the one the system used.
            let ms =
                command == .skipForward
                ? PlaybackSession.skipForwardIntervalMs
                : PlaybackSession.skipBackwardIntervalMs
            skip.preferredIntervals = [NSNumber(value: Double(ms) / 1_000)]
        }
        remote.isEnabled = true
        targets[command, default: []].append(
            remote.addTarget(handler: Self.target(command, handler)))
    }

    func removeTargets(_ command: RemoteCommand) {
        let remote = Self.remote(command)
        for token in targets.removeValue(forKey: command) ?? [] { remote.removeTarget(token) }
    }

    private static func remote(_ command: RemoteCommand) -> MPRemoteCommand {
        let centre = MPRemoteCommandCenter.shared()
        switch command {
        case .play: return centre.playCommand
        case .pause: return centre.pauseCommand
        case .togglePlayPause: return centre.togglePlayPauseCommand
        case .changePlaybackPosition: return centre.changePlaybackPositionCommand
        case .skipForward: return centre.skipForwardCommand
        case .skipBackward: return centre.skipBackwardCommand
        }
    }

    /// The closure the command centre calls, made outside the main actor on purpose.
    ///
    /// MediaPlayer names no thread for a target's call, and a closure written inside a main-actor
    /// method would be inferred main-actor-isolated and trap if it ever arrived elsewhere. So the
    /// event is read where it arrives, and handled at once on the main thread — the case in
    /// practice, and the only one that can answer whether it was carried out — or queued there
    /// and answered as handled.
    nonisolated private static func target(
        _ command: RemoteCommand,
        _ handler: @escaping @MainActor @Sendable (RemoteCommandEvent) -> Bool
    ) -> @Sendable (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        { platformEvent in
            let event = Self.event(command, platformEvent)
            guard Thread.isMainThread else {
                DispatchQueue.main.async { MainActor.assumeIsolated { _ = handler(event) } }
                return .success
            }
            return MainActor.assumeIsolated { handler(event) } ? .success : .commandFailed
        }
    }

    nonisolated private static func event(
        _ command: RemoteCommand, _ event: MPRemoteCommandEvent
    ) -> RemoteCommandEvent {
        switch command {
        case .play: return .play
        case .pause: return .pause
        case .togglePlayPause: return .togglePlayPause
        case .changePlaybackPosition:
            let position = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?? 0
            return .changePlaybackPosition(toSeconds: position)
        case .skipForward, .skipBackward:
            let interval = (event as? MPSkipIntervalCommandEvent)?.interval ?? 0
            return .skip(bySeconds: command == .skipForward ? interval : -interval)
        }
    }
}

/// The process's one Now Playing, and which session holds it.
///
/// The Kotlin sessions are objects of their own and Media3 refuses a second with the same id; here
/// there is one dictionary and one command centre per process and no id to tell two sessions
/// apart, so **the session made most recently holds it**. An earlier session still alive publishes
/// nothing and hears no command until it is released — releasing it then changes nothing — and the
/// holder's release clears the dictionary and removes every target, so no button outlives the
/// session that drew it. A trap on a second session was the alternative and is refused: a screen
/// that builds its next session before releasing its last, which SwiftUI's lifecycle does freely,
/// would crash for an order nobody chose.
@MainActor
package final class NowPlayingCentre {

    /// The system's, which every session a consumer makes shares.
    static let system = NowPlayingCentre(platform: SystemNowPlayingPlatform())

    private let platform: any NowPlayingPlatform

    /// The holder, by token — a token rather than the session, since a session that is gone without
    /// a ``release`` withdraws one main-actor turn after its `deinit` and must not find a newer
    /// session's place.
    private var holder: UInt64?
    private weak var holderSession: PlaybackSession?
    private var nextToken: UInt64 = 0

    package init(platform: any NowPlayingPlatform) {
        self.platform = platform
    }

    /// Makes `session` the holder, adding the command targets if no session held them.
    func claim(_ session: PlaybackSession) -> UInt64 {
        nextToken += 1
        if holder == nil {
            for command in RemoteCommand.allCases {
                platform.addTarget(command) { [weak self] event in
                    self?.holderSession?.handle(event) ?? false
                }
            }
        }
        holder = nextToken
        holderSession = session
        return nextToken
    }

    /// Publishes `info` if `token` holds Now Playing; a displaced session's is dropped.
    func publish(_ info: [String: Any]?, from token: UInt64) {
        guard holder == token else { return }
        platform.setNowPlayingInfo(info)
    }

    /// Lets go of Now Playing if `token` holds it: the dictionary cleared and every target removed.
    func relinquish(_ token: UInt64) {
        guard holder == token else { return }
        holder = nil
        holderSession = nil
        platform.setNowPlayingInfo(nil)
        for command in RemoteCommand.allCases { platform.removeTargets(command) }
    }
}
