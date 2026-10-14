# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# i4's device exit (#435, `docs/ios-plan.md` §7): the feed's time to first frame measured, and its
# memory flat over a 200-row scroll in xctrace, on a phone. `ios/hunt-leaks` is the same scroll on a
# simulator, where memory is the host's; this is the number a phone reports.
#
# The feed scrolls itself (`FeedScroll`, `-SPFeedScroll`), because nothing outside an iOS app can
# scroll it: there is no `adb shell input` for a phone. Each row's first frame is the collector's
# `first_frame` event in the demo's log; the memory is the demo's physical footprint in the run's
# Activity Monitor recording, judged per pass by `ios_feed_report` (lib/ios.sh).

SCENARIO_DESCRIPTION="iOS: the feed scrolled down FEED_ROWS rows (default 200) and back FEED_PASSES times (default 2); TTFF per row, and whether memory stays flat."
SCENARIO_PLATFORMS=ios
FEED_ROWS="${FEED_ROWS:-200}"
FEED_PASSES="${FEED_PASSES:-2}"
FEED_DWELL_MS="${FEED_DWELL_MS:-1000}"
# Long enough for the first row's position to be logged twice — the readiness every run waits on —
# since the feed logs a row's position once a second and recycles it after a dwell.
FEED_LEAD_IN_MS="${FEED_LEAD_IN_MS:-5000}"
SCENARIO_IOS_LAUNCH=(-SPDemoScreen feed -SPFeedRows "$FEED_ROWS" -SPFeedScroll "$FEED_PASSES"
    -SPFeedDwellMs "$FEED_DWELL_MS" -SPFeedLeadInMs "$FEED_LEAD_IN_MS")
# The recording launches the demo (`ios_record_launch`), because a recording over every process ends
# after about a second on a phone and this one needs the whole scroll. So the run is as long as the
# recording, and the recording as long as the scroll is expected to be: the lead-in, every step of
# every pass at its dwell with 15% for the time a step itself takes (36 steps took 37 s on the phone
# #435 ran on), and a minute for the launch, the first row and a sample or two at rest afterwards.
SCENARIO_IOS_RECORDED_LAUNCH=1
SCENARIO_TRACE_FROM=launch
SCENARIO_TRACE_MS=$((FEED_LEAD_IN_MS + (FEED_ROWS - 1) * 2 * FEED_PASSES * FEED_DWELL_MS * 115 / 100 + 60000))

# Nothing to drive: the feed scrolled itself inside the recording. A scroll the recording did not see
# finish is said here and judged `unknown` by the report.
scenario_drive() {
    grep -q "scroll finished" "$IOS_LOG" ||
        log "warning: the recording ended before the scroll finished; memory will read unknown"
}

scenario_report() {
    # Every row's first frame, one per line, for a reader who wants the rows rather than the spread.
    ios_first_frames <"$RUN_DIR/console-main.log" >"$RUN_DIR/ttff.tsv"
    printf 'The feed: %s rows, %s pass(es) down and back, %s ms a row, on the device above. `ttff.tsv`\n' \
        "$FEED_ROWS" "$FEED_PASSES" "$FEED_DWELL_MS"
    printf 'holds every first frame by content id: the time and its boundary.\n\n'
    ios_feed_report "$RUN_DIR/console-main.log" "$RUN_DIR/footprint.tsv" "${IOS_TRACE_START_MS:-0}" \
        "$RUN_DIR/feed-verdict.txt" "${IOS_TRACE_ENDED_EARLY:-0}"
}

scenario_verdict() {
    [ "$(cat "$RUN_DIR/feed-verdict.txt" 2>/dev/null)" = flat ]
}
