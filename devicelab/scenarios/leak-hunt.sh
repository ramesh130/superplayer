# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# The leak hunt (#50): drives the demo through everything SuperPlayer does that an app cannot easily
# undo, takes a Java heap dump before and after at the same point of the scenario, and names what grew
# and the fields that retain it. devicelab/leak/README.md is its manual — what is exercised and what
# is not, how to read the report, and why the tolerance is what it is.
#
# Two phases, each compared against its own baseline, because they leave the demo in different
# states and a leak in one is invisible from the other:
#
#   lifecycle  rotation mid-playback, background and foreground with the session live, content named
#              by id through the session, the session released and a new one built, and the feed
#              entered and left. Compared on the player screen before and after, with the service's
#              player stopped through its session for each dump (leak_capture_stopped says why).
#   feed       the whole 200-row feed scrolled down and back up, repeatedly, with the pool acquiring,
#              recycling and reusing players. Compared at the top of the feed, before and after.
#
# Each phase is run once untimed first, so that what a first run builds once and keeps — classes,
# caches, a pool grown to its bound — is in the baseline rather than counted as growth.

SCENARIO_DESCRIPTION="Rotates, backgrounds, re-resolves, releases the session and scrolls the 200-row feed; compares heap dumps and names what grew and what retains it."
SCENARIO_TRACE_FROM=playing
# A bound, not a duration: the scenario ends the trace itself, well before this.
SCENARIO_TRACE_MS=5400000
# The run's own trace records memory over the whole scenario; the heap dumps and native profiles are
# the scenario's own traces, taken at the moments it chooses.
SCENARIO_DATA_SOURCES=process_memory

# shellcheck source=../leak/leak.sh
. "$DEVICELAB_HOME/leak/leak.sh"

LEAK_CYCLES="${LEAK_CYCLES:-3}"
LEAK_FEED_ROWS="${LEAK_FEED_ROWS:-200}"
# Six, because a pass sends only about 23 players through the pool — most rows scrolled past find it at
# its bound — and a leak of one object per recycle needs room above the feed's threshold of 40. Six put
# it near 140. ../leak/README.md has the measurements.
LEAK_FEED_PASSES="${LEAK_FEED_PASSES:-6}"
LEAK_SETTLE_S="${LEAK_SETTLE_S:-5}"
LEAK_HEAP_DUMP_MS="${LEAK_HEAP_DUMP_MS:-20000}"
LEAK_NATIVE_BOUND_MS="${LEAK_NATIVE_BOUND_MS:-3600000}"

# The demo's two streams, by the content ids its catalog resolves, and the titles that catalog gives
# them — which is how the session proves it resolved an id rather than merely playing one.
LEAK_HLS_ID=demo:bipbop-advanced
LEAK_HLS_TITLE="BipBop Advanced"
LEAK_DASH_ID=demo:tears-of-steel
LEAK_DASH_TITLE="Tears of Steel"

# --- Hooks -----------------------------------------------------------------------------------------

# Before a boot and a build are spent: the trace processor the report needs, downloaded and checked.
scenario_prepare() {
    trace_processor_bin >/dev/null
}

scenario_drive() {
    mkdir -p "$RUN_DIR/leak"
    leak_hold_portrait
    leak_probe_gc

    log "leak hunt: lifecycle warm-up cycle"
    leak_lifecycle_cycle
    leak_capture_stopped lifecycle-baseline
    trace_begin native-lifecycle heapprofd "$LEAK_NATIVE_BOUND_MS"
    local cycle=1
    while [ "$cycle" -le "$LEAK_CYCLES" ]; do
        log "leak hunt: lifecycle cycle $cycle of $LEAK_CYCLES"
        leak_lifecycle_cycle
        cycle=$((cycle + 1))
    done
    leak_settle
    trace_end native-lifecycle
    leak_capture_stopped lifecycle-final

    leak_open_feed
    log "leak hunt: feed warm-up pass"
    leak_feed_pass warmup
    leak_capture feed-baseline
    trace_begin native-feed heapprofd "$LEAK_NATIVE_BOUND_MS"
    local pass=1
    while [ "$pass" -le "$LEAK_FEED_PASSES" ]; do
        log "leak hunt: feed pass $pass of $LEAK_FEED_PASSES"
        leak_feed_pass "$pass"
        pass=$((pass + 1))
    done
    leak_settle
    trace_end native-feed
    leak_capture feed-final

    leak_open_player
}

scenario_report() {
    leak_report
}

scenario_verdict() {
    [ ! -s "$RUN_DIR/leak/findings.tsv" ]
}

scenario_cleanup() {
    [ -n "${LEAK_SAVED_ROTATION:-}" ] || return 0
    adb_s shell settings put system user_rotation "${LEAK_SAVED_ROTATION% *}" >/dev/null 2>&1 || true
    adb_s shell settings put system accelerometer_rotation "${LEAK_SAVED_ROTATION#* }" >/dev/null 2>&1 || true
}

# --- The device ------------------------------------------------------------------------------------

# Portrait, and held there, so that only the scenario's own rotations rotate the screen. What the
# device had is put back by scenario_cleanup, on success or failure.
leak_hold_portrait() {
    LEAK_SAVED_ROTATION="$(adb_s shell settings get system user_rotation | tr -d '\r') $(adb_s shell settings get system accelerometer_rotation | tr -d '\r')"
    adb_s shell settings put system accelerometer_rotation 0
    leak_rotate 0
}

leak_rotate() {
    adb_s shell settings put system user_rotation "$1"
    # The Activity is recreated on the next frame; this is the time that takes, plus the new one
    # binding. What follows then waits on the session, not on this.
    sleep 3
}

# Whether a GC can be forced in the demo, which is ART's answer to SIGUSR1 ("SIGUSR1 forcing GC"). The
# shell user may not signal another app's process, so it takes root: an emulator's userdebug image has
# `su`, a production device does not. Without it the heap dumps are still right, because an object is
# counted only if it is reachable (histogram.sql) — the GC is for what a collection does beyond
# reachability, which is to clear weak references and run finalizers — and the report says which.
#
# ref: https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/signal_catcher.cc (SIGUSR1)
leak_probe_gc() {
    LEAK_GC_FORCED=0
    [ "$(adb_s shell su 0 id -u 2>/dev/null | tr -d '\r')" = 0 ] && LEAK_GC_FORCED=1
    [ "$LEAK_GC_FORCED" = 1 ] || log "warning: no root on this device, so no GC can be forced before a heap dump"
}

leak_demo_pid() {
    adb_s shell pidof "$DEMO_PACKAGE" | tr -d '\r'
}

# Settles, then collects: the other processes holding the demo's Binder objects first, then the demo,
# twice.
#
# The other processes first, because a released session's stub is a Binder object and stays rooted in
# the demo — with the last timeline it saw — for as long as another process holds a proxy to it. An ART
# process drops a proxy only when it collects. Without this, the first clean runs found one or two
# more stubs at the final dump than at the baseline, each carrying a whole HLS manifest, and never the
# same number twice: system_server, SystemUI and the rest had simply not collected yet. The holders
# are read from the kernel's binder log for the demo's process at every settle, since they change;
# only ART processes (the zygote's children) are signalled, because to a native daemon SIGUSR1 is not a
# request to collect but a signal to terminate. Native holders release by reference count anyway.
#
# The demo twice, with a pause between, because the first collection finds what is only
# finalizer-reachable and queues it, and only a second one after the finalizer thread has run frees it.
#
# ref: https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/signal_catcher.cc (SIGUSR1: "forcing GC")
# ref: https://man7.org/linux/man-pages/man7/signal.7.html (SIGUSR1's default action: terminate)
# ref: https://docs.kernel.org/admin-guide/binderfs.html (binder_logs)
# ref: https://android.googlesource.com/platform/libcore/+/refs/heads/main/libart/src/main/java/java/lang/Daemons.java (the finalizer daemon)
leak_settle() {
    sleep "$LEAK_SETTLE_S"
    [ "$LEAK_GC_FORCED" = 1 ] || return 0
    local pid holders holder
    pid="$(leak_demo_pid)"
    [ -n "$pid" ] || die "the demo is not running, so there is no heap to settle"

    adb_s shell "su 0 sh -c 'cat /dev/binderfs/binder_logs/proc/$pid 2>/dev/null || cat /sys/kernel/debug/binder/proc/$pid'" |
        tr -d '\r' > "$RUN_WORK/binder.txt" || true
    adb_s shell ps -A -o PID,PPID,NAME | tr -d '\r' > "$RUN_WORK/ps.txt"
    holders="$(art_ref_holders "$pid" "$(adb_s shell pidof zygote64 zygote | tr -d '\r')" \
        "$RUN_WORK/binder.txt" "$RUN_WORK/ps.txt")"
    for holder in $holders; do
        adb_s shell su 0 kill -USR1 "$holder" || true
        printf '%s %s\n' "$holder" "$(awk -v p="$holder" '$1 == p { print $3 }' "$RUN_WORK/ps.txt")" \
            >> "$RUN_DIR/leak/remote-gc.txt"
    done
    # Their cleaners release the proxies, and the kernel tells the demo, which drops its roots.
    [ -z "$holders" ] || sleep 3

    adb_s shell su 0 kill -USR1 "$pid"
    sleep 2
    adb_s shell su 0 kill -USR1 "$pid"
    sleep 2
}

# Waits up to `$2` seconds for the demo's session to be in platform state `$1`.
leak_wait_session_state() {
    local deadline=$(($(date +%s) + $2)) sample
    while :; do
        sample="$(adb_s shell dumpsys media_session | session_state "$DEMO_PACKAGE")"
        [ "${sample% *}" != "$1" ] || return 0
        [ "$(date +%s)" -lt "$deadline" ] || die "the demo's session did not reach state $1 within $2 s (last: ${sample:-none})"
        sleep 1
    done
}

# Waits up to `$1` seconds for the demo to have no media session at all.
#
# The dump is read whole and then searched, rather than piped into `grep -q`: grep stops reading at its
# first match, and under pipefail the writer it leaves behind can fail the pipeline — which here would
# read as "no session" while one is still published.
leak_wait_no_session() {
    local deadline=$(($(date +%s) + $1)) sessions
    while :; do
        sessions="$(adb_s shell dumpsys media_session | tr -d '\r')"
        grep -q "package=$DEMO_PACKAGE\$" <<<"$sessions" || return 0
        [ "$(date +%s)" -lt "$deadline" ] || die "the demo's session was still published $1 s after its service was stopped"
        sleep 1
    done
}

# Waits until the demo's session describes content titled `$1` and is playing it, advancing.
leak_wait_for_content() {
    local deadline=$(($(date +%s) + 90)) description
    while :; do
        description="$(adb_s shell dumpsys media_session | session_description "$DEMO_PACKAGE")"
        case "$description" in "$1"*) break ;; esac
        [ "$(date +%s)" -lt "$deadline" ] || die "the session never described '$1' (last: ${description:-none})"
        sleep 2
    done
    wait_for_playback 90 >/dev/null
}

# The heap comparison counts the demo's Activities against this, so a step that left two — a launch
# that stacked a second rather than replacing the first — would read as a leak. It fails the run
# instead: that is the scenario misdriven, not the library leaking.
leak_assert_one_activity() {
    local live
    live="$(adb_s shell dumpsys activity activities | tr -d '\r' | live_activity_count "$DEMO_PACKAGE")"
    [ "$live" = 1 ] || die "the system lists $live of the demo's activities where the scenario expects one"
}

# --- The lifecycle phase ---------------------------------------------------------------------------

# One cycle, which starts and ends on the player screen, portrait, playing HLS through the service's
# session — so that what it leaves behind, if anything, is the only difference between two captures.
leak_lifecycle_cycle() {
    # Rotation mid-playback, both ways. Each destroys the Activity and builds another; the player is
    # the service's and carries on, which the session confirms by still advancing.
    leak_rotate 1
    wait_for_playback 60 >/dev/null
    assert_demo_in_focus
    leak_rotate 0
    wait_for_playback 60 >/dev/null
    assert_demo_in_focus

    # Background with the session live: still playing with no Activity at all. Then back.
    adb_s shell input keyevent KEYCODE_HOME
    sleep 3
    wait_for_playback 60 >/dev/null
    foreground_demo
    wait_for_playback 60 >/dev/null
    assert_demo_in_focus

    # Content named by id through the session, as a car or a watch names it: a MediaController
    # sends a bare id, and MediaRequestResolver turns it back into a request. The title arriving in
    # the session's metadata is the resolver's work; a bare id has none.
    relaunch_demo --es com.superplayer.demo.extra.CONTENT_ID "$LEAK_DASH_ID"
    leak_wait_for_content "$LEAK_DASH_TITLE"
    relaunch_demo --es com.superplayer.demo.extra.CONTENT_ID "$LEAK_HLS_ID"
    leak_wait_for_content "$LEAK_HLS_TITLE"

    # The session released: background, pause through the session as the notification would, and
    # stop the service, whose onDestroy releases the session and then its player. Then back, which
    # builds a new service, session and player.
    adb_s shell input keyevent KEYCODE_HOME
    sleep 2
    adb_s shell cmd media_session dispatch pause >/dev/null
    leak_wait_session_state "$SESSION_STATE_PAUSED" 30
    # Its exit status is not an answer: on API 36 it exits 255 having stopped the service. Whether the
    # service went is what the session disappearing says.
    adb_s shell am stopservice -n "$DEMO_PACKAGE/.DemoPlaybackService" >/dev/null 2>&1 || true
    leak_wait_no_session 30
    foreground_demo
    wait_for_playback 90 >/dev/null
    assert_demo_in_focus

    # The feed, entered and left: its pool is built with the screen and must go with it.
    leak_open_feed
    local swipe=0
    while [ "$swipe" -lt 10 ]; do
        ui_swipe_up 250
        swipe=$((swipe + 1))
    done
    sleep 2
    leak_open_player
    leak_assert_one_activity
}

# --- The feed phase --------------------------------------------------------------------------------

# The feed, with the service's player paused first. Every player requests audio focus while it plays,
# and focus is one token, so the feed's playing row would pause the service's player anyway; pausing
# it here makes that deterministic rather than a race.
leak_open_feed() {
    adb_s shell cmd media_session dispatch pause >/dev/null
    leak_wait_session_state "$SESSION_STATE_PAUSED" 30
    relaunch_demo --es com.superplayer.demo.extra.SCREEN FEED \
        --ei com.superplayer.demo.extra.FEED_ROWS "$LEAK_FEED_ROWS"
    sleep 5
    assert_demo_in_focus
}

# The player screen, playing again.
leak_open_player() {
    relaunch_demo
    adb_s shell cmd media_session dispatch play >/dev/null
    wait_for_playback 90 >/dev/null
    assert_demo_in_focus
}

# How many swipes are certain to reach the end of the feed. A swipe moves the list half a screen
# before any fling, and a row is 16:9 of the screen's width, so in portrait a swipe moves at least
# (height / 2) / (width * 9 / 16) rows. Half again on top of that, so that neither a short fling nor a
# row taller than its video makes the scroll stop short. Surplus swipes at an end are no-ops.
leak_feed_swipes() {
    local size width height
    size="$(ui_screen_size)"
    width="${size% *}"
    height="${size#* }"
    printf '%s\n' $(((LEAK_FEED_ROWS * width * 9 / 16 * 3 / 2) / (height / 2) + 1))
}

# Down the whole feed and back up to the top. Each row that enters the screen acquires a player from
# the pool, prepares it and registers a listener, and each that leaves recycles it. A screenshot at the
# bottom is the evidence that the scroll got there.
leak_feed_pass() {
    local swipes swipe=0
    swipes="$(leak_feed_swipes)"
    while [ "$swipe" -lt "$swipes" ]; do
        ui_swipe_up 250
        swipe=$((swipe + 1))
    done
    sleep 2
    adb_s exec-out screencap -p > "$RUN_DIR/leak/feed-bottom-$1.png" || true
    swipe=0
    while [ "$swipe" -lt "$swipes" ]; do
        ui_swipe_down 250
        swipe=$((swipe + 1))
    done
    LEAK_FEED_SWIPES="$swipes"
}

# --- Capturing -------------------------------------------------------------------------------------

# A lifecycle capture, taken with the service's player stopped through its session, then played again.
#
# Stopped, because a playing player is a moving target: how many variant playlists its HLS tracker
# holds, and how much of each, depends on where adaptive selection has been, so two dumps of the same
# player a few minutes apart differ by a hundred playlist segments that nobody leaked. That was the
# first run's lifecycle noise, and every object of it was the live player's. `stop()` releases the
# media source and everything it loaded, and keeps the playlist, so baseline and final hold the same
# thing: one player, one item, nothing loaded.
leak_capture_stopped() {
    adb_s shell cmd media_session dispatch stop >/dev/null
    leak_wait_session_stopped 30
    leak_capture "$1"
    adb_s shell cmd media_session dispatch play >/dev/null
    wait_for_playback 90 >/dev/null
}

# Waits up to `$1` seconds for the demo's session to report neither playing nor buffering: Media3
# reports a stopped player as NONE or STOPPED, depending on what it has.
leak_wait_session_stopped() {
    local deadline=$(($(date +%s) + $1)) sample
    while :; do
        sample="$(adb_s shell dumpsys media_session | session_state "$DEMO_PACKAGE")"
        case "${sample% *}" in "$SESSION_STATE_NONE" | "$SESSION_STATE_STOPPED") return 0 ;; esac
        [ "$(date +%s)" -lt "$deadline" ] || die "the demo's session did not stop within $1 s (last: ${sample:-none})"
        sleep 1
    done
}

# Settles and collects, then records at one moment what the platform says — the Activities it lists as
# alive, and the process's memory — and what the heap says, as a Java heap dump.
leak_capture() {
    local name="$1" pid
    leak_settle
    pid="$(leak_demo_pid)"
    printf '%s\n' "$pid" > "$RUN_DIR/leak/$name.pid"
    adb_s shell dumpsys activity activities | tr -d '\r' | live_activity_count "$DEMO_PACKAGE" \
        > "$RUN_DIR/leak/$name.live-activities"
    adb_s shell dumpsys meminfo "$DEMO_PACKAGE" | tr -d '\r' > "$RUN_DIR/leak/$name.meminfo.txt"
    log "leak hunt: heap dump $name (pid $pid)"
    capture_trace "heap-$name" java_hprof "$LEAK_HEAP_DUMP_MS"
    leak_check_heap "$RUN_DIR/heap-$name.perfetto-trace"
    [ "$(leak_demo_pid)" = "$pid" ] || die "the demo restarted while its heap was being dumped"
}
