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
import SuperPlayerTestkit
import XCTest

/// Scores ``FrameSourceConformance`` itself, from both sides — the Kotlin
/// `FrameSourceConformanceTest` (#347), through the public API alone.
///
/// **It could pass everything.** A check nothing has ever failed is a check that does not work, so
/// there is one deliberately wrong publisher per obligation (``PublishingFrameSource/Defect``), each
/// driven against the check meant to catch it, and the register holds the two sets equal.
///
/// **It could describe something nothing can satisfy.** So the correct publisher passes the whole
/// suite in both of obligation 7's branches, and asynchronously, which is the only shape in which
/// cancellation passing means anything. `SuperPlayerRealtimeTests`' `ScriptedFrameSourceConformanceTests`
/// is the other half: the fake the realtime tests play through, held to the same contract.
final class FrameSourceConformanceTests: XCTestCase {

    func testACorrectlyWrittenPublisherSatisfiesEveryObligation() throws {
        try conformance().verifyAll()
    }

    func testASelfDescribingPublisherSatisfiesEveryObligation() throws {
        try conformance(selfDescribing: true).verifyAll()
    }

    /// The control obligation 9 needs and the two above cannot be: a publisher that delivers inside
    /// `subscribe` has nothing left to stop by the time anything cancels it, so it satisfies
    /// cancellation by arithmetic. This one delivers from a thread of its own and joins it in
    /// `cancel`, the shape a transport over a socket has.
    func testAnAsynchronousPublisherSatisfiesEveryObligation() throws {
        try conformance(asynchronous: true).verifyAll()
    }

    func testAPublisherDeliveringFromTwoThreadsAtOnceIsRefusedByObligationOne() {
        let refusal = refusal(from: .deliversFromTwoThreadsAtOnce, by: .deliveriesDoNotOverlap)
        XCTAssertTrue(refusal.contains("obligation 1"), refusal)
        // What it did, and not merely that it failed: the reader has no copy of this repository.
        XCTAssertTrue(refusal.contains("two deliveries in flight"), refusal)
    }

    func testAPublisherThatDeclaresItsTracksLateIsRefusedByObligationTwo() {
        let refusal = refusal(
            from: .deliversAFrameBeforeDeclaringItsTracks,
            by: .tracksAreDeclaredOnceBeforeTheFirstFrame)
        XCTAssertTrue(refusal.contains("obligation 2"), refusal)
        XCTAssertTrue(refusal.contains("before it called onTracks"), refusal)
    }

    func testAPublisherSendingAFrameOnAnUndeclaredTrackIsRefusedByObligationTwo() {
        let refusal = refusal(
            from: .sendsAFrameOnAnUndeclaredTrack, by: .everyFrameNamesADeclaredTrack)
        XCTAssertTrue(refusal.contains("obligation 2"), refusal)
        XCTAssertTrue(refusal.contains("naming trackIndex 7"), refusal)
    }

    func testAPublisherStartingOnADependentFrameIsRefusedByObligationThree() {
        let refusal = refusal(
            from: .startsATrackWithoutAKeyframe, by: .eachTrackStartsWithAKeyframe)
        XCTAssertTrue(refusal.contains("obligation 3"), refusal)
        XCTAssertTrue(refusal.contains("keyFrame = false"), refusal)
    }

    func testAPublisherWhoseTimestampsGoBackwardsIsRefusedByObligationFour() {
        let refusal = refusal(
            from: .stepsOneTracksTimestampsBackwards, by: .timestampsAreMonotonicPerTrack)
        XCTAssertTrue(refusal.contains("obligation 4"), refusal)
        XCTAssertTrue(refusal.contains("backwards"), refusal)
    }

    func testAPublisherDeclaringACodecNothingMapsIsRefused() {
        let refusal = refusal(from: .declaresAnUnmappedCodec, by: .codecStringsAreMapped)
        XCTAssertTrue(refusal.contains("RealtimeTrack.codec"), refusal)
        XCTAssertTrue(refusal.contains("\"theora\""), refusal)
    }

    func testAPublisherHandingARecordForASelfDescribingFourccIsRefusedByObligationSeven() {
        let refusal = refusal(
            from: .handsARecordForASelfDescribingFourcc, by: .codecConfigurationFollowsTheFourcc)
        XCTAssertTrue(refusal.contains("obligation 7"), refusal)
        XCTAssertTrue(refusal.contains("CodecConfiguration.record"), refusal)
    }

    /// #340's own defect, which this whole branch exists to prevent: valid H.264 declared with a
    /// record, the two disagreeing about how the samples are delimited.
    func testAPublisherFramingItsSamplesTheOtherWayIsRefusedByObligationSeven() {
        let refusal = refusal(
            from: .framesSamplesAsAnnexBUnderARecord, by: .samplesAreFramedAsTheFourccRequires)
        XCTAssertTrue(refusal.contains("obligation 7"), refusal)
        XCTAssertTrue(refusal.contains("Annex-B start code"), refusal)
    }

    func testAPublisherHandingOnePooledBufferOverTwiceIsRefusedByObligationEight() {
        let refusal = refusal(
            from: .handsTheSameBufferOverTwice, by: .payloadsAreNeitherReusedNorTouchedAgain)
        XCTAssertTrue(refusal.contains("obligation 8"), refusal)
        XCTAssertTrue(refusal.contains("rewritten in between"), refusal)
    }

    /// Obligation 8's other half, which the pooled defect cannot reach: that one is refused on
    /// shared memory before the comparison is ever made, so without this the mutation branch would
    /// be a refusal nothing had forced.
    func testAPublisherWritingIntoAPayloadItHasHandedOverIsRefusedByObligationEight() {
        let refusal = refusal(
            from: .writesIntoAPayloadItHasHandedOver, by: .payloadsAreNeitherReusedNorTouchedAgain)
        XCTAssertTrue(refusal.contains("obligation 8"), refusal)
        XCTAssertTrue(refusal.contains("after onFrame had returned"), refusal)
    }

    func testAPublisherDeliveringAfterItHasEndedIsRefused() {
        let refusal = refusal(from: .deliversAFrameAfterEnding, by: .terminationIsFinal)
        XCTAssertTrue(refusal.contains("onEnded"), refusal)
        XCTAssertTrue(refusal.contains("after it had called onEnded"), refusal)
    }

    func testAPublisherWhoseCancelStopsNothingIsRefusedByObligationNine() {
        let refusal = refusal(from: .keepsDeliveringAfterCancel, by: .cancellationStopsDelivery)
        XCTAssertTrue(refusal.contains("obligation 9"), refusal)
        XCTAssertTrue(refusal.contains("after cancel() returned"), refusal)
    }

    /// `verifyAll` stops at the first obligation broken, so one defect states that the entry point a
    /// consumer calls reaches the conclusion a single check does.
    func testTheWholeSuiteRefusesAPublisherThatBreaksOneObligation() {
        XCTAssertThrowsError(try conformance(defect: .startsATrackWithoutAKeyframe).verifyAll()) {
            XCTAssertTrue("\($0)".contains("obligation 3"), "\($0)")
        }
    }

    /// The **register**: every check has a wrong publisher that fails it, and every wrong publisher
    /// has a check that catches it — what makes a twelfth check with no defect beside it, or a defect
    /// nothing drives, fail the build. The checks are read off `Check.allCases`, the enumeration
    /// `verify(_:)` switches over exhaustively, where the Kotlin register reads them off the class by
    /// reflection. A check maps to a *list*, the Kotlin departure from the HTTP suite's register:
    /// obligation 8's two refusals cannot both fire on one publisher. The methods above are the
    /// proof that each pairing holds; this is what makes a missing one visible.
    func testEveryCheckHasAWrongPublisherAndEveryWrongPublisherHasACheck() {
        let register: [FrameSourceConformance.Check: [PublishingFrameSource.Defect]] = [
            .deliveriesDoNotOverlap: [.deliversFromTwoThreadsAtOnce],
            .tracksAreDeclaredOnceBeforeTheFirstFrame: [.deliversAFrameBeforeDeclaringItsTracks],
            .everyFrameNamesADeclaredTrack: [.sendsAFrameOnAnUndeclaredTrack],
            .eachTrackStartsWithAKeyframe: [.startsATrackWithoutAKeyframe],
            .timestampsAreMonotonicPerTrack: [.stepsOneTracksTimestampsBackwards],
            .codecStringsAreMapped: [.declaresAnUnmappedCodec],
            .codecConfigurationFollowsTheFourcc: [.handsARecordForASelfDescribingFourcc],
            .samplesAreFramedAsTheFourccRequires: [.framesSamplesAsAnnexBUnderARecord],
            .payloadsAreNeitherReusedNorTouchedAgain: [
                .handsTheSameBufferOverTwice, .writesIntoAPayloadItHasHandedOver,
            ],
            .terminationIsFinal: [.deliversAFrameAfterEnding],
            .cancellationStopsDelivery: [.keepsDeliveringAfterCancel],
        ]

        XCTAssertEqual(Set(register.keys), Set(FrameSourceConformance.Check.allCases))
        XCTAssertEqual(
            Set(register.values.joined()), Set(PublishingFrameSource.Defect.allCases))
        XCTAssertEqual(
            register.values.joined().count, PublishingFrameSource.Defect.allCases.count,
            "a defect registered under two checks")
    }

    /// The refusal `check` raises over a publisher with `defect`, holding it to the message shape
    /// every refusal keeps — the point is that it is read by someone who is not this code's author.
    private func refusal(
        from defect: PublishingFrameSource.Defect, by check: FrameSourceConformance.Check,
        file: StaticString = #filePath, line: UInt = #line
    ) -> String {
        do {
            try conformance(defect: defect).verify(check)
        } catch {
            XCTAssertTrue(error.message.contains("What this source did:"), file: file, line: line)
            XCTAssertTrue(
                error.message.contains("What the rule requires:"), file: file, line: line)
            return error.message
        }
        XCTFail("\(defect) was not refused by \(check)", file: file, line: line)
        return ""
    }

    /// The suite over a fresh publisher per check, as a player opens one per playback: one instance
    /// subscribed twice would have one check's cancellation reach the next one's delivery.
    private func conformance(
        defect: PublishingFrameSource.Defect? = nil, selfDescribing: Bool = false,
        asynchronous: Bool = false
    ) -> FrameSourceConformance {
        FrameSourceConformance(
            sources: Publishers(
                defect: defect, selfDescribing: selfDescribing, asynchronous: asynchronous),
            url: URL(string: "moq://relay.example/studio-a")!)
    }
}

/// Opens a new ``PublishingFrameSource`` per subscription.
private struct Publishers: FrameSourceFactory {
    let defect: PublishingFrameSource.Defect?
    let selfDescribing: Bool
    let asynchronous: Bool

    func open(_ url: URL) -> any FrameSource {
        PublishingFrameSource(
            defect: defect, selfDescribing: selfDescribing, asynchronous: asynchronous)
    }
}
