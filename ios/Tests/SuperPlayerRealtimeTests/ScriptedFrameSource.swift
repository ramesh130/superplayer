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
import SuperPlayerCore
import SuperPlayerTestMedia

/// A transport that is a script — the Kotlin `ScriptedFrameSource`: it declares its tracks, then
/// delivers frames on a thread of its own, in real time, until it is cancelled.
///
/// **Real H.264, not described video.** Android's fake feeds Media3's fake renderers, which decode
/// nothing; here the display layer decodes what it is handed, so the frames are
/// `SyntheticVideoHlsStream`'s hand-written bitstream without its container — an `avc1` track whose
/// record is that stream's `avcC` and whose samples are its length-prefixed NAL units, CoreMedia's own
/// framing (ADR-0018 rule 4). #340's observed bytes are not decodable — no slice of theirs was dumped
/// — so they are what the formats tests read (`ObservedBytes`), and this is what plays.
///
/// **On an epoch of its own**: the first timestamp is two hours in, as an RTP stream's random
/// initial timestamp is (RFC 3550 §5.1), so a player that forgot to subtract it would wait two hours
/// for its first frame — the defect a fake starting at zero hides.
///
/// **Endless and asynchronous by default**, joining its thread in ``cancel()``, because a fake that
/// finishes inside `subscribe` satisfies obligation 9 by arithmetic (the Kotlin #347 reason).
///
/// `@unchecked Sendable`: every `var` is read and written under `lock`, and the rest is immutable.
final class ScriptedFrameSource: FrameSource, @unchecked Sendable {

    /// Where the script's timeline starts: two hours, in microseconds.
    static let epochUs: Int64 = 7_200_000_000

    /// How many frames are delivered at once before the rest are paced: half a second's, so the
    /// first picture is already queued when the clock starts at it rather than arriving on its
    /// presentation time exactly, which is a race against the clock the test is not about.
    static let prerollFrames = SyntheticVideoHlsStream.framesPerSecond / 2

    private let tracks: [RealtimeTrack]
    private let frameLimit: Int?
    private let corruptsSlices: Bool

    private let lock = NSLock()
    private var cancelled = false
    private let finished = DispatchSemaphore(value: 0)
    private var started = false
    private var _subscribeCount = 0
    private var _cancelCount = 0
    private var _delivered = 0

    /// A source declaring `tracks` — the synthetic `avc1` track unless told otherwise — and
    /// delivering `frameLimit` frames, or frames for ever. With `corruptsSlices`, every byte of
    /// every frame past its length field and NAL header is overwritten — a stream whose framing is
    /// right and whose pictures are noise, the control showing the layer really decodes.
    init(
        tracks: [RealtimeTrack] = [ScriptedFrameSource.syntheticH264], frameLimit: Int? = nil,
        corruptsSlices: Bool = false
    ) {
        self.tracks = tracks
        self.frameLimit = frameLimit
        self.corruptsSlices = corruptsSlices
    }

    /// The synthetic stream's video as a track: `avc1` with its record, at its coded size.
    static let syntheticH264 = RealtimeTrack(
        codec: "avc1.42c01e",
        codecConfiguration: .record(Data(SyntheticVideoHlsStream.avcDecoderConfigurationRecord())),
        codedSize: RealtimeTrack.CodedSize(
            width: SyntheticVideoHlsStream.width, height: SyntheticVideoHlsStream.height))

    var subscribeCount: Int { lock.withLock { _subscribeCount } }
    var cancelCount: Int { lock.withLock { _cancelCount } }
    var deliveredFrames: Int { lock.withLock { _delivered } }

    func subscribe(_ sink: any FrameSink) {
        lock.withLock {
            _subscribeCount += 1
            started = true
        }
        let thread = Thread { [self] in
            defer { finished.signal() }
            sink.onTracks(tracks)
            let frameInterval = 1 / Double(SyntheticVideoHlsStream.framesPerSecond)
            var index = 0
            while !isCancelled, frameLimit.map({ index < $0 }) ?? true {
                var (sample, isKeyFrame) = SyntheticVideoHlsStream.elementaryFrame(index)
                if corruptsSlices {
                    // Past the four-byte length and the one-byte NAL header, so the framing still
                    // reads and only the slice — what a decoder decodes — is noise.
                    for offset in 5..<sample.count {
                        sample[offset] = UInt8(truncatingIfNeeded: offset &* 37)
                    }
                }
                let timestampUs =
                    Self.epochUs + Int64(index) * 1_000_000
                    / Int64(SyntheticVideoHlsStream.framesPerSecond)
                sink.onFrame(
                    EncodedFrame(
                        timestampUs: timestampUs, payload: Data(sample), keyFrame: isKeyFrame))
                lock.withLock { _delivered += 1 }
                index += 1
                if index >= Self.prerollFrames { Thread.sleep(forTimeInterval: frameInterval) }
            }
        }
        thread.name = "ScriptedFrameSource"
        thread.start()
    }

    /// Stops the thread and waits for it, so no callback arrives after this returns (obligation 9).
    func cancel() {
        let wasStarted = lock.withLock {
            _cancelCount += 1
            cancelled = true
            return started
        }
        if wasStarted { finished.wait() }
    }

    private var isCancelled: Bool { lock.withLock { cancelled } }
}

/// A source that declares tracks and nothing else, and refuses nothing — for a test about what the
/// player makes of a declaration. `@unchecked Sendable` only because the protocol asks; immutable.
final class DeclaringFrameSource: FrameSource, @unchecked Sendable {
    private let tracks: [RealtimeTrack]
    init(tracks: [RealtimeTrack]) { self.tracks = tracks }
    func subscribe(_ sink: any FrameSink) { sink.onTracks(tracks) }
    func cancel() {}
}

/// The transport a test configures a player with: it opens what the test scripts for each URL and
/// records every URL it was asked for. `@unchecked Sendable`: what it records is under its lock.
final class ScriptedTransport: FrameSourceFactory, @unchecked Sendable {
    private let lock = NSLock()
    private let make: @Sendable (URL) -> any FrameSource
    private var _opened: [(url: URL, source: any FrameSource)] = []

    init(_ make: @escaping @Sendable (URL) -> any FrameSource = { _ in ScriptedFrameSource() }) {
        self.make = make
    }

    func open(_ url: URL) -> any FrameSource {
        let source = make(url)
        lock.withLock { _opened.append((url, source)) }
        return source
    }

    var opened: [(url: URL, source: any FrameSource)] { lock.withLock { _opened } }
}
