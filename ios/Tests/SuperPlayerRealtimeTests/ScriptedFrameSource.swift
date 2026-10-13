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
/// delivers their frames on a thread of its own, interleaved in the order a publisher would send
/// them, until each track's script runs out or it is cancelled.
///
/// **Real H.264 and real AAC, not described media.** Android's fake feeds Media3's fake renderers,
/// which decode nothing; here the renderers decode what they are handed, so the video is
/// `SyntheticVideoHlsStream`'s hand-written bitstream without its container and the audio
/// `SyntheticHlsStream`'s silent AAC frame without its ADTS header. The video comes in each of
/// ADR-0018 rule 4's shapes (``ScriptedTrack/VideoShape``): an `avc1` record and length-prefixed
/// samples, the same at a two-byte length width, and `avc3` with its parameter sets in band and
/// Annex-B start codes. #340's observed bytes are not decodable — no slice of theirs was dumped —
/// so they are what the framing and formats tests read (`ObservedBytes`), and this is what plays.
///
/// **On an epoch of its own**: the first timestamp is two hours in, as an RTP stream's random
/// initial timestamp is (RFC 3550 §5.1), so a player that forgot to subtract it would wait two hours
/// for its first frame — the defect a fake starting at zero hides. A track can be put on an epoch
/// of its own besides, which is what the shared-epoch rule is tested with.
///
/// **Asynchronous, and paced in real time by default**, joining its thread in ``cancel()``, because
/// a fake that finishes inside `subscribe` satisfies obligation 9 by arithmetic (the Kotlin #347
/// reason). An unpaced script delivers as fast as the sink takes it, which is what a test of the
/// late and stalled bounds uses: those are media time, so a verdict reached at speed is the one
/// reached in real time.
///
/// `@unchecked Sendable`: every `var` is read and written under `lock`, and the rest is immutable.
final class ScriptedFrameSource: FrameSource, @unchecked Sendable {

    /// Where the script's timeline starts: two hours, in microseconds.
    static let epochUs: Int64 = 7_200_000_000

    /// How much of the script is delivered at once before the rest is paced: half a second, so the
    /// first picture is already queued when the clock starts at it rather than arriving on its
    /// presentation time exactly, which is a race against the clock the test is not about.
    static let prerollUs: Int64 = 500_000

    /// Half a second of video frames, the preroll counted in them.
    static let prerollFrames = SyntheticVideoHlsStream.framesPerSecond / 2

    /// A pause in delivery, once, as script time reaches `atUs`: the publisher going quiet for
    /// `seconds` of real time and then sending what it owes at once — the renderers' starvation
    /// a rebuffer is read from (#461).
    struct Stall: Sendable {
        let atUs: Int64
        let seconds: Double
    }

    /// What a source made with `failsAfterFrames:` reports.
    struct ScriptedFailure: Error {}

    private let scripts: [ScriptedTrack]
    private let paced: Bool
    private let stall: Stall?
    private let failsAfterFrames: Int?
    private let prerollUs: Int64

    private let lock = NSLock()
    private var cancelled = false
    private let finished = DispatchSemaphore(value: 0)
    private var started = false
    private var _subscribeCount = 0
    private var _cancelCount = 0
    private var _delivered = 0
    private var _lastTimestamps: [Int64?]

    /// A source declaring `tracks` — the synthetic `avc1` video, endless, unless told otherwise —
    /// and delivering their frames in script-time order, paced in real time when `paced`.
    ///
    /// With a `stall`, delivery pauses once where it says; with `failsAfterFrames`, the source
    /// reports ``ScriptedFailure`` through `onError` once it has delivered that many frames, and
    /// delivers nothing more. `prerollUs` is how much is sent at once before pacing — zero is a
    /// publisher delivering each frame just in time, as a live encoder does.
    init(
        tracks: [ScriptedTrack] = [.video()], paced: Bool = true, stall: Stall? = nil,
        failsAfterFrames: Int? = nil, prerollUs: Int64 = ScriptedFrameSource.prerollUs
    ) {
        self.scripts = tracks
        self.paced = paced
        self.prerollUs = prerollUs
        self.stall = stall
        self.failsAfterFrames = failsAfterFrames
        self._lastTimestamps = Array(repeating: nil, count: tracks.count)
    }

    /// The synthetic video, endless, with every byte of every frame past its length field and NAL
    /// header overwritten — a stream whose framing is right and whose pictures are noise, the
    /// control showing the layer really decodes.
    convenience init(corruptsSlices: Bool) {
        self.init(tracks: [.video(corruptsSlices ? .corruptAvc1 : .avc1)])
    }

    var subscribeCount: Int { lock.withLock { _subscribeCount } }
    var cancelCount: Int { lock.withLock { _cancelCount } }
    var deliveredFrames: Int { lock.withLock { _delivered } }

    /// The transport timestamp of the last frame delivered on each track, nil for one that has
    /// delivered none — what a test waits for the engine to have caught up with.
    var lastTimestamps: [Int64?] { lock.withLock { _lastTimestamps } }

    func subscribe(_ sink: any FrameSink) {
        lock.withLock {
            _subscribeCount += 1
            started = true
        }
        let thread = Thread { [self] in
            defer { finished.signal() }
            sink.onTracks(scripts.map(\.track))
            var next = Array(repeating: 0, count: scripts.count)
            var stalled = false
            var delivered = 0
            let start = Date()
            while !isCancelled {
                // The track whose next frame is due soonest in script time, ties to the earlier
                // declared — one publisher's interleaving, deterministic.
                let due = scripts.indices.filter { index in
                    scripts[index].frames.map { limit in next[index] < limit } ?? true
                }
                guard
                    let track = due.min(by: {
                        scripts[$0].scriptTimeUs(next[$0]) < scripts[$1].scriptTimeUs(next[$1])
                    })
                else { break }
                let script = scripts[track]
                let scriptTimeUs = script.scriptTimeUs(next[track])
                if let stall, !stalled, scriptTimeUs >= stall.atUs {
                    stalled = true
                    // In slices of 10 ms, so a cancellation during the stall is answered within
                    // one — well inside any bound a test waits on, and short enough not to hold
                    // `cancel()` noticeably.
                    let resumesAt = Date().addingTimeInterval(stall.seconds)
                    while Date() < resumesAt, !isCancelled { Thread.sleep(forTimeInterval: 0.01) }
                    if isCancelled { break }
                }
                if paced, scriptTimeUs > prerollUs {
                    let wait =
                        Double(scriptTimeUs - prerollUs) / 1_000_000
                        - Date().timeIntervalSince(start)
                    if wait > 0 { Thread.sleep(forTimeInterval: wait) }
                }
                let (payload, keyFrame) = script.frame(next[track])
                let timestampUs = script.epochUs + scriptTimeUs
                sink.onFrame(
                    EncodedFrame(
                        timestampUs: timestampUs, payload: payload, keyFrame: keyFrame,
                        trackIndex: track))
                lock.withLock {
                    _delivered += 1
                    _lastTimestamps[track] = timestampUs
                }
                next[track] += 1
                delivered += 1
                if let failsAfterFrames, delivered >= failsAfterFrames {
                    sink.onError(ScriptedFailure())
                    break
                }
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

/// One track of a ``ScriptedFrameSource``'s script: what it declares, how many frames it sends and
/// how far apart, and where on the transport's timeline its frames are stamped.
struct ScriptedTrack: Sendable {

    /// What the source declares for this track.
    let track: RealtimeTrack

    /// How many frames are sent, or nil for frames until the source is cancelled.
    let frames: Int?

    /// The transport timestamp of script time zero: ``ScriptedFrameSource/epochUs`` unless the
    /// track is on an epoch of its own.
    let epochUs: Int64

    /// Script time of the first frame — a publisher's start skew on a shared epoch.
    let startsAtUs: Int64

    /// The frame's spacing in microseconds of script time, as a function of its index so that a
    /// rate that does not divide a second (AAC's 1024 / 44100 s) accumulates no rounding.
    let timeOfFrameUs: @Sendable (Int) -> Int64

    /// Frame `index`'s payload and whether it is a keyframe.
    let frame: @Sendable (Int) -> (payload: Data, keyFrame: Bool)

    func scriptTimeUs(_ index: Int) -> Int64 { startsAtUs + timeOfFrameUs(index) }

    /// The shapes the synthetic video is sent in — ADR-0018 rule 4's two branches, and the
    /// controls.
    enum VideoShape: Sendable {
        /// `avc1` with the stream's `avcC` and its four-byte length-prefixed samples.
        case avc1
        /// `avc1` with the record rewritten to declare two-byte lengths and every sample re-framed
        /// at that width: the case a hard-coded four would pass every other test while failing on.
        case avc1TwoByteLengths
        /// `avc3`: no record, Annex-B start codes, and the SPS and PPS before every keyframe.
        case avc3
        /// ``avc1`` with every slice overwritten with noise: the decode control.
        case corruptAvc1
    }

    /// The synthetic video in `shape`, thirty frames a second.
    static func video(
        _ shape: VideoShape = .avc1, frames: Int? = nil,
        epochUs: Int64 = ScriptedFrameSource.epochUs,
        startsAtUs: Int64 = 0
    ) -> ScriptedTrack {
        let configuration: RealtimeTrack.CodecConfiguration
        switch shape {
        case .avc1, .corruptAvc1: configuration = .record(Data(SyntheticH264.record))
        case .avc1TwoByteLengths: configuration = .record(Data(SyntheticH264.record(lengthSize: 2)))
        case .avc3: configuration = .inBand
        }
        return ScriptedTrack(
            track: RealtimeTrack(
                codec: shape == .avc3 ? "avc3.42c01e" : "avc1.42c01e",
                codecConfiguration: configuration,
                codedSize: RealtimeTrack.CodedSize(
                    width: SyntheticVideoHlsStream.width, height: SyntheticVideoHlsStream.height)),
            frames: frames, epochUs: epochUs, startsAtUs: startsAtUs,
            timeOfFrameUs: {
                Int64($0) * 1_000_000 / Int64(SyntheticVideoHlsStream.framesPerSecond)
            },
            frame: { index in
                let (sample, keyFrame) = SyntheticVideoHlsStream.elementaryFrame(index)
                return (
                    Data(SyntheticH264.reshaped(sample, keyFrame: keyFrame, as: shape)), keyFrame
                )
            })
    }

    /// The synthetic silent AAC-LC, 44.1 kHz stereo, its `AudioSpecificConfig` as the record.
    static func audio(
        frames: Int? = nil, epochUs: Int64 = ScriptedFrameSource.epochUs, startsAtUs: Int64 = 0
    ) -> ScriptedTrack {
        let frame = Data(SyntheticHlsStream.elementaryAudioFrame())
        return ScriptedTrack(
            track: RealtimeTrack(
                codec: "mp4a.40.2",
                codecConfiguration: .record(Data(SyntheticHlsStream.audioSpecificConfig()))),
            frames: frames, epochUs: epochUs, startsAtUs: startsAtUs,
            timeOfFrameUs: {
                Int64($0) * Int64(SyntheticHlsStream.samplesPerFrame) * 1_000_000
                    / Int64(SyntheticHlsStream.samplingFrequencyHz)
            },
            frame: { _ in (frame, true) })
    }

    /// How many audio frames span `seconds` of script time, rounded up.
    static func audioFrames(spanning seconds: Double) -> Int {
        Int(
            (seconds * Double(SyntheticHlsStream.samplingFrequencyHz)
                / Double(SyntheticHlsStream.samplesPerFrame)).rounded(.up))
    }
}

/// The synthetic stream's H.264 in each shape the fake sends it in.
enum SyntheticH264 {

    /// The stream's `avcC`, as its sample entry carries it.
    static let record = SyntheticVideoHlsStream.avcDecoderConfigurationRecord()

    /// The record with its `lengthSizeMinusOne` rewritten and nothing else touched — the Kotlin
    /// test's `avcCDeclaringLengthSize`. The six bits above it are reserved and set to one.
    static func record(lengthSize: Int) -> [UInt8] {
        var bytes = record
        bytes[4] = 0xFC | UInt8(lengthSize - 1)
        return bytes
    }

    /// The SPS and PPS the record carries, read at the fixed offsets of a record holding one of
    /// each (ISO/IEC 14496-15 §5.3.3.1.2): a two-byte length at 6, then the PPS's count and length.
    static let parameterSets: [[UInt8]] = {
        let spsLength = Int(record[6]) << 8 | Int(record[7])
        let sps = Array(record[8..<8 + spsLength])
        let ppsAt = 8 + spsLength + 1
        let ppsLength = Int(record[ppsAt]) << 8 | Int(record[ppsAt + 1])
        return [sps, Array(record[ppsAt + 2..<ppsAt + 2 + ppsLength])]
    }()

    /// `sample` — four-byte length-prefixed, as the stream carries it — in `shape`.
    static func reshaped(_ sample: [UInt8], keyFrame: Bool, as shape: ScriptedTrack.VideoShape)
        -> [UInt8]
    {
        let units = units(of: sample)
        switch shape {
        case .avc1:
            return sample
        case .corruptAvc1:
            // Past the four-byte length and the one-byte NAL header, so the framing still reads
            // and only the slice — what a decoder decodes — is noise.
            var noisy = sample
            for offset in 5..<noisy.count {
                noisy[offset] = UInt8(truncatingIfNeeded: offset &* 37)
            }
            return noisy
        case .avc1TwoByteLengths:
            return units.flatMap {
                [UInt8(truncatingIfNeeded: $0.count >> 8), UInt8(truncatingIfNeeded: $0.count)] + $0
            }
        case .avc3:
            let startCode: [UInt8] = [0, 0, 0, 1]
            return ((keyFrame ? parameterSets : []) + units).flatMap { startCode + $0 }
        }
    }

    /// The NAL units of a four-byte length-prefixed sample.
    private static func units(of sample: [UInt8]) -> [[UInt8]] {
        var units: [[UInt8]] = []
        var offset = 0
        while offset + 4 <= sample.count {
            let length = sample[offset..<offset + 4].reduce(0) { $0 << 8 | Int($1) }
            units.append(Array(sample[offset + 4..<offset + 4 + length]))
            offset += 4 + length
        }
        return units
    }
}

/// A source that declares tracks and nothing else, and refuses nothing — for a test about what the
/// player makes of a declaration. Immutable, so checked `Sendable`.
final class DeclaringFrameSource: FrameSource, Sendable {
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
