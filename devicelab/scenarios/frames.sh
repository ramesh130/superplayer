# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# i1's device exit (#435, `docs/ios-plan.md` §7): the pass-through frame output drops no frame an
# `AVPlayerLayer` would have shown, counted on a phone.
#
# The demo's frame-output screen counts it (`FrameTally` in FrameOutputScreen.swift): the frames the
# output was handed, and — from their presentation times — the frames the content held between them
# that it was not. It logs both every two seconds, and this scenario plays for FRAMES_SECONDS and reads
# the log. Then the same stream on the playback screen, whose `VideoPlayer` is an `AVPlayerLayer`, for
# the same time: AVFoundation's own count of the frames that layer dropped is the control, the number a
# frame output is compared with rather than assumed to beat. README.md's *The frames scenario* argues
# the method and states what it cannot see.

SCENARIO_DESCRIPTION="iOS: FRAMES_SECONDS (default 120) through the pass-through frame output, counting the frames it never saw; then AVPlayerLayer's own drops as the control."
SCENARIO_PLATFORMS=ios
# Two minutes is 7200 frames at the stream's 60 a second: enough that one lost frame is a reading and
# not a rounding, short enough that both arms fit in a few minutes.
FRAMES_SECONDS="${FRAMES_SECONDS:-120}"
SCENARIO_IOS_LAUNCH=(-SPDemoScreen frameOutput)
# From playing, so the recording is the playback the count covers; a bound with room for both arms.
SCENARIO_TRACE_FROM=playing
SCENARIO_TRACE_MS=$((($FRAMES_SECONDS * 2 + 300) * 1000))

# DemoPlayback.positionLogIntervalSeconds, in milliseconds: what a steady stretch advances by.
FRAMES_LOG_INTERVAL_MS=2000

scenario_drive() {
    sleep "$FRAMES_SECONDS"
    FRAMES_READING="$(ios_frame_reading <"$IOS_LOG")"
    [ -n "$FRAMES_READING" ] || die "the frame-output screen logged no frames= line"
    FRAMES_STEADY="$(ios_frame_steady "$FRAMES_LOG_INTERVAL_MS" <"$IOS_LOG")"

    log "the control: the same stream on AVPlayerLayer for $FRAMES_SECONDS s"
    ios_launch_demo control -SPDemoScreen playback
    ios_wait_for_playback 90 >/dev/null
    sleep "$FRAMES_SECONDS"
    CONTROL_READING="$(ios_dropped_reading <"$IOS_LOG")"
}

scenario_report() {
    local first last frames missing runs repeated dropped interval gap c_first c_last c_dropped
    local steady_ms steady_frames steady_missing other_ms other_missing
    read -r first last frames missing runs repeated dropped interval gap <<<"$FRAMES_READING"
    read -r steady_ms steady_frames steady_missing other_ms other_missing <<<"$FRAMES_STEADY"
    read -r c_first c_last c_dropped <<<"${CONTROL_READING:-- - -}"
    cat <<REPORT
| | Frame output (\`PlayerView\`, pass-through) | Control (\`VideoPlayer\`, an \`AVPlayerLayer\`) |
| --- | ---: | ---: |
| Position logged | $first → $last ms | $c_first → $c_last ms |
| Frames handed to the output | $frames | — |
| Frames the content held between them, never handed | $missing | — |
| … in steady playback ($steady_ms ms, $steady_frames frames handed) | **$steady_missing** | — |
| … around a stall or a seek ($other_ms ms) | $other_missing | — |
| Stretches of one picture size (variant switches + 1) | $runs | — |
| Frames handed twice | $repeated | — |
| Frame interval (the last stretch's) · largest gap @ where it ended | $interval ms · $gap ms | — |
| Dropped, by AVFoundation's access log | $dropped | **$c_dropped** |

The two columns are two counters over two sessions, one after the other on the same network, and are
not one comparison: the left is inferred from presentation times, the right is AVFoundation's own
\`numberOfDroppedVideoFrames\`. The control says what an \`AVPlayerLayer\` drops on this phone, which is
what the output's count is read beside; the verdict is the left column's alone.

$(frames_verdict_line "$FRAMES_STEADY")

**Steady** is a stretch between two of the demo's two-second lines where the position advanced by
exactly two seconds and the collector reported no rebuffer and no seek. The verdict is taken there,
because a starved item hands over late frames by skipping them, and an \`AVPlayerLayer\` on the same
starved item would have shown no more; the rest is reported and not judged (\`ios_frame_steady\`).

What the count is: \`FrameTally\` takes the shortest gap between two handed presentation times as a
stretch's frame interval and counts the frames that interval says the stretch held but the output was
never handed. What it cannot see: a frame lost exactly at a variant switch, since the gap across a
switch is judged by nothing ($((runs - 1)) switch(es) this run), and whether the display layer then
presented each frame on time. The access log's count on the frame-output player is reported as read,
not interpreted: with no \`AVPlayerLayer\` attached it describes AVFoundation's pipeline, not a screen.
REPORT
}

# The verdict on `ios_frame_steady`'s reading `$1`, as one line: **met** when nothing went missing in
# steady playback covering at least half the time played — less than that is a session too disturbed
# to judge, which is **unknown** and worth running again — and **not met** otherwise.
frames_verdict_line() {
    local steady_ms steady_frames steady_missing
    read -r steady_ms steady_frames steady_missing _ <<<"$1"
    if [ "${steady_ms:-0}" -lt $(($FRAMES_SECONDS * 500)) ]; then
        printf 'Verdict: **unknown** — %s ms of steady playback, under half the %s s played: too disturbed to judge; run it again.\n' \
            "${steady_ms:-0}" "$FRAMES_SECONDS"
    elif [ "$steady_missing" = 0 ]; then
        printf 'Verdict: **met** — in %s ms of steady playback, every frame the content held reached the output (%s handed).\n' \
            "$steady_ms" "$steady_frames"
    else
        printf 'Verdict: **not met** — in %s ms of steady playback, %s frame(s) the content held never reached the output (of %s handed).\n' \
            "$steady_ms" "$steady_missing" "$steady_frames"
    fi
}

scenario_verdict() {
    case "$(frames_verdict_line "$FRAMES_STEADY")" in *"**met**"*) return 0 ;; esac
    return 1
}
