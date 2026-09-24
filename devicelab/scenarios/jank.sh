# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# Scrolls the demo's feed and taps it, in one trace that records what UI jank is read from: every
# frame's deadline and what the app did inside it, the main thread's scheduling, and the garbage
# collector. devicelab/jank/README.md is its manual — why this scroll and these taps, and how to read
# the report.
#
# Before the trace: the service's player is paused, the demo is relaunched on the feed, and the whole
# plan below is run once untraced and scrolled back to the top, so that what a first pass compiles,
# builds and fetches once is not in the measurement. Then the trace starts, and the same plan runs again
# from the top of the feed: JANK_ROUNDS rounds of JANK_SWIPES swipes down the feed, each round ending
# with two taps — pause the row being watched, play it again.
#
# With `--trace-from launch` the launch, the relaunch onto the feed and the warm-up are in the trace as
# well, and every table in the report then describes them too.

SCENARIO_DESCRIPTION="Scrolls the feed and taps it, for the jank metrics: frame timeline, main-thread scheduling, GC."
SCENARIO_TRACE_FROM=playing
# `frametimeline` for every frame's expected and actual deadline, which jank is read from; `jank` for the
# app's side of each frame, its threads' scheduling and GC (../perfetto/sources/jank.pbtxt).
SCENARIO_DATA_SOURCES=jank,frametimeline

# shellcheck source=../jank/jank.sh
. "$DEVICELAB_HOME/jank/jank.sh"

# The feed's length, as the leak hunt passes it: the demo's default, and far longer than the scroll
# reaches, so the traced pass never meets the end of the list.
JANK_FEED_ROWS="${JANK_FEED_ROWS:-200}"
# Three rounds, so that each measurement is made three times inside one trace: six taps, and three
# separate stretches of scrolling that each start from rest.
JANK_ROUNDS="${JANK_ROUNDS:-3}"
# Eight swipes a round: about twenty rows each, so a round reaches rows the last one did not, and each
# row that becomes the watched one acquires, prepares and starts a player, which is the work a feed does
# as it scrolls.
JANK_SWIPES="${JANK_SWIPES:-8}"
# A swipe's duration. 300 ms is ui.sh's default, a quick flick that flings: the frames of a fling are the
# ones a viewer sees stutter, and a fling is what the feed is scrolled with.
JANK_SWIPE_MS="${JANK_SWIPE_MS:-300}"
# Seconds between swipes: long enough for most of a fling, short enough that the list is still moving
# when the next one lands, as a viewer's thumb does.
JANK_SWIPE_GAP_S="${JANK_SWIPE_GAP_S:-1}"
# Seconds of rest around each tap: the fling is over before it, so the tap is a tap and not a catch of a
# moving list, and its frames are not a scroll's.
JANK_REST_S="${JANK_REST_S:-2}"

# --- Hooks -----------------------------------------------------------------------------------------

# Before a boot and a build are spent: knobs that make sense, and the trace processor the report needs,
# downloaded and checked.
scenario_prepare() {
    jank_require_count JANK_FEED_ROWS "$JANK_FEED_ROWS"
    jank_require_count JANK_ROUNDS "$JANK_ROUNDS"
    jank_require_count JANK_SWIPES "$JANK_SWIPES"
    jank_require_count JANK_SWIPE_MS "$JANK_SWIPE_MS"
    jank_require_count JANK_SWIPE_GAP_S "$JANK_SWIPE_GAP_S" 0
    jank_require_count JANK_REST_S "$JANK_REST_S" 0
    trace_processor_bin >/dev/null
}

# The feed, warmed up, at its top. None of this is in the trace.
scenario_setup() {
    mkdir -p "$RUN_DIR/jank"
    jank_plan "$JANK_ROUNDS" "$JANK_SWIPES" > "$RUN_DIR/jank/plan.txt"
    # With the service's player paused (open_feed), which matters here beyond the leak hunt's reason: a
    # second player decoding behind the feed would be work in every frame that the feed did not ask for.
    open_feed "$JANK_FEED_ROWS"
    log "jank: warm-up pass, untraced"
    jank_run_plan
    jank_back_to_top
    assert_demo_in_focus
}

scenario_drive() {
    log "jank: $(jank_plan_count swipe < "$RUN_DIR/jank/plan.txt") swipes and $(jank_plan_count tap < "$RUN_DIR/jank/plan.txt") taps, traced"
    jank_run_plan
    assert_demo_in_focus
}

scenario_report() {
    printf 'The feed (%s rows), scrolled from its top in %s rounds of %s swipes of %s ms, each round ending in two taps on the middle of the feed: %s swipes and %s taps in the trace, after the same plan once untraced as a warm-up.\n\n' \
        "$JANK_FEED_ROWS" "$JANK_ROUNDS" "$JANK_SWIPES" "$JANK_SWIPE_MS" \
        "$(jank_plan_count swipe < "$RUN_DIR/jank/plan.txt")" "$(jank_plan_count tap < "$RUN_DIR/jank/plan.txt")"
    if [ ! -f "$RUN_DIR/trace.perfetto-trace" ]; then
        printf 'No trace.\n'
        return
    fi
    jank_report_query frames 'Frames of the window (`actual_frame_timeline_slice`)'
    jank_report_query frame_work 'Work per frame (`Choreographer#doFrame`, `DrawFrame`)'
    jank_report_query main_thread 'The main thread (`thread_state`)'
    jank_report_query blocked 'Where the main thread slept in the middle of work, by the slice it slept in'
    jank_report_query gc 'Garbage collection (`dalvik` slices)'
}

# --- The device ------------------------------------------------------------------------------------

# Carries out the plan in plan.txt, one gesture a line. Read on a descriptor of its own, because `adb
# shell` reads its standard input and would swallow the rest of the plan.
jank_run_plan() {
    local step
    while read -r step <&3; do
        case "$step" in
            swipe) ui_swipe_up "$JANK_SWIPE_MS"; sleep "$JANK_SWIPE_GAP_S" ;;
            rest) sleep "$JANK_REST_S" ;;
            tap) ui_tap_centre ;;
            *) die "unknown step '$step' in the jank plan" ;;
        esac
    done 3< "$RUN_DIR/jank/plan.txt"
}

# Back to the feed's top after the warm-up, so the traced pass scrolls the same rows, and refuses to go on
# unless it got there. Twice as many swipes as went down, one straight after another: a new touch stops
# the fling before it, so a swipe back cannot be counted on to travel as far as one of the plan's, and
# surplus swipes at the top are no-ops. 200 ms is a quicker flick than the plan's, because nothing here
# is measured and the return should not take as long as the pass.
jank_back_to_top() {
    local swipe=0 swipes=$((JANK_ROUNDS * JANK_SWIPES * 2)) watched
    while [ "$swipe" -lt "$swipes" ]; do
        ui_swipe_down 200
        swipe=$((swipe + 1))
    done
    # The watched row logs itself once a second (FeedScreen.kt's SuperPlayerFeed line), so after a rest
    # the newest line names the row at the top.
    sleep "$((JANK_REST_S + 2))"
    watched="$(adb_s logcat -d -t 50 -s SuperPlayerFeed | tr -d '\r' | sed -n 's/.*row=\([0-9]*\) .*/\1/p' | tail -1)"
    [ "$watched" = 0 ] || die "the feed did not scroll back to its top after the warm-up (watching row ${watched:-none})"
}

# --- The report ------------------------------------------------------------------------------------

# Runs sql/`$1`.sql over the trace, keeps its CSV in jank/, and prints it as a table under heading `$2`.
jank_report_query() {
    printf '### %s\n\n' "$2"
    trace_processor_query "$RUN_DIR/trace.perfetto-trace" "$DEVICELAB_HOME/jank/sql/$1.sql" \
        "PACKAGE=$DEMO_PACKAGE" > "$RUN_DIR/jank/$1.csv"
    tp_csv_to_tsv < "$RUN_DIR/jank/$1.csv" | tsv_to_markdown
    printf '\n'
}
