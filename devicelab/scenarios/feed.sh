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
# From launch, so the recording holds the footprint before the first row as well as through the
# scroll. A ceiling, never an expected duration: two dwells a row a pass, doubled, and ten minutes for
# a slow CDN — `ios/hunt-leaks`' own.
SCENARIO_TRACE_FROM=launch
SCENARIO_TRACE_MS=$((((FEED_ROWS * 2 * FEED_PASSES * FEED_DWELL_MS / 1000) * 2 + 600) * 1000))

scenario_drive() {
    ios_wait_for_log "scroll finished" $((TRACE_MS / 1000))
    # A sample or two of the feed at rest, after the last row was recycled.
    sleep "${FEED_SETTLE_S:-10}"
}

scenario_report() {
    printf 'The feed: %s rows, %s pass(es) down and back, %s ms a row, on the device above.\n\n' \
        "$FEED_ROWS" "$FEED_PASSES" "$FEED_DWELL_MS"
    ios_feed_report "$RUN_DIR/console-main.log" "$RUN_DIR/footprint.tsv" "${IOS_TRACE_START_MS:-0}" \
        "$RUN_DIR/feed-verdict.txt"
}

scenario_verdict() {
    [ "$(cat "$RUN_DIR/feed-verdict.txt" 2>/dev/null)" = flat ]
}
