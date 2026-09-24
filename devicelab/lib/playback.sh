# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# Launching the demo, and knowing that it is playing rather than guessing that it has had long enough.
#
# The signal is the demo's media session, as the platform reports it in `dumpsys media_session`. The
# demo publishes a `PlaybackSession`, and Media3 mirrors the player into the platform session: the
# state reads PLAYING(3) only once the player is ready *and* asked to play. While it plays, the
# session's position is refreshed about every three seconds — observed, not documented: sampled on
# the API 36 emulator with Media3 1.11.0, `updated=` advanced by ~3000 ms between samples with the
# position following it. A position that has risen between two samples is therefore content
# advancing, not a player that reached READY and stalled.
#
# ref: https://developer.android.com/reference/android/media/session/PlaybackState A screenshot cannot tell
# those apart, and a fixed sleep cannot tell either of them from a slow network.
#
# `dumpsys` rather than logcat: it is the current state, a few kilobytes, and needs no clearing
# first. An unbounded `adb logcat -d` on an emulator that has been up a while dumps the whole buffer
# and can take minutes.

# Reads `dumpsys media_session` on stdin and prints "<state> <position>" for the first session owned
# by package `$1`, or nothing when it has no session or the session has no playback state yet.
session_state() {
    awk -v want="$1" '
        /^[[:space:]]*package=/ {
            pkg = $0
            sub(/^[[:space:]]*package=/, "", pkg)
            sub(/[[:space:]].*$/, "", pkg)
            next
        }
        pkg == want && /state=PlaybackState \{state=/ {
            state = $0
            sub(/.*PlaybackState \{state=[A-Z_]*\(/, "", state)
            sub(/\).*/, "", state)
            position = $0
            sub(/.*\{state=[^,]*, position=/, "", position)
            sub(/,.*/, "", position)
            print state, position
            exit
        }
    '
}

# Media3's platform-session states, as `android.media.session.PlaybackState` numbers them.
SESSION_STATE_NONE=0
SESSION_STATE_STOPPED=1
SESSION_STATE_PAUSED=2
SESSION_STATE_PLAYING=3
SESSION_STATE_ERROR=7

# Grants what the demo would otherwise raise a dialog for, before it starts.
#
# The first launch on API 33+ asks for POST_NOTIFICATIONS, and the dialog sits over the player
# surface: a screenshot taken under it shows a dialog and a black rectangle, which is exactly what a
# playback failure looks like. Granting it up front means the dialog never appears, which is more
# reliable than finding and tapping it — and the run then measures the app in the state a viewer who
# said yes leaves it in, notification included.
grant_first_run_permissions() {
    if [ "${DEVICE_SDK:-0}" -ge 33 ]; then
        adb_s shell pm grant "$DEMO_PACKAGE" android.permission.POST_NOTIFICATIONS
    fi
}

launch_demo() {
    adb_s shell am force-stop "$DEMO_PACKAGE"
    adb_s shell am start -W -n "$DEMO_PACKAGE/$DEMO_ACTIVITY" >/dev/null ||
        die "the demo did not launch"
}

# Starts the demo's Activity again with `am start` arguments `$@` — launch extras, typically; the demo
# documents the ones it reads in `DemoLaunch` — in a task of its own, clearing the old one
# (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK). The Activity that was showing is destroyed,
# not left underneath the new one, so there is still exactly one. The process, and a service in it,
# carry on.
relaunch_demo() {
    adb_s shell am start -W -n "$DEMO_PACKAGE/$DEMO_ACTIVITY" -f 0x10008000 "$@" >/dev/null ||
        die "the demo did not relaunch"
}

# Brings the demo's task back to the front, as returning to a backgrounded app does: the Activity that
# is there resumes, and no second one starts. The intent is the one `launch_demo` and `relaunch_demo`
# start the task with — a component and no action — because a task is resumed rather than added to
# only when the intent matches the one at its root, and extras take no part in that match.
foreground_demo() {
    adb_s shell am start -W -n "$DEMO_PACKAGE/$DEMO_ACTIVITY" >/dev/null ||
        die "the demo did not come back to the foreground"
}

# Reads `dumpsys media_session` on stdin and prints the description of the first session owned by
# package `$1` — title, subtitle and a third field, comma-separated as the platform writes them — or
# nothing when it has no session or no metadata.
session_description() {
    awk -v want="$1" '
        /^[[:space:]]*package=/ {
            pkg = $0
            sub(/^[[:space:]]*package=/, "", pkg)
            sub(/[[:space:]].*$/, "", pkg)
            next
        }
        pkg == want && /^[[:space:]]*metadata: .*description=/ {
            sub(/.*description=/, "")
            print
            exit
        }
    '
}

# Fails when anything but the demo has focus — a permission dialog the grant above did not prevent,
# a crash dialog, a system prompt. Any of them would put the run's frames somewhere other than the
# screen being measured.
assert_demo_in_focus() {
    local focus
    focus="$(adb_s shell dumpsys window | grep -m1 'mCurrentFocus' | tr -d '\r')"
    case "$focus" in
        *"$DEMO_PACKAGE/"*) ;;
        *) die "the demo does not have focus; something is over it: ${focus# *}" ;;
    esac
}

# Waits until the demo is playing and its position has advanced; prints the position reached.
wait_for_playback() {
    local timeout="${1:-60}" deadline now sample state position first=""
    deadline=$(($(date +%s) + timeout))
    while :; do
        sample="$(adb_s shell dumpsys media_session | session_state "$DEMO_PACKAGE")"
        state="${sample% *}"
        position="${sample#* }"
        if [ "$state" = "$SESSION_STATE_ERROR" ]; then
            die "the demo's session reports an error; playback failed rather than started"
        fi
        case "$position" in '' | *[!0-9]*) state="" ;; esac
        if [ "$state" = "$SESSION_STATE_PLAYING" ]; then
            if [ -z "$first" ]; then
                first="$position"
            elif [ "$position" -gt "$first" ]; then
                printf '%s\n' "$position"
                return 0
            fi
        fi
        now=$(date +%s)
        if [ "$now" -ge "$deadline" ]; then
            die "no advancing playback within ${timeout}s (last session state: ${sample:-none})"
        fi
        sleep 2
    done
}

# Waits up to `$2` seconds for the demo's session to be in platform state `$1`.
wait_session_state() {
    local deadline=$(($(date +%s) + $2)) sample
    while :; do
        sample="$(adb_s shell dumpsys media_session | session_state "$DEMO_PACKAGE")"
        [ "${sample% *}" != "$1" ] || return 0
        [ "$(date +%s)" -lt "$deadline" ] || die "the demo's session did not reach state $1 within $2 s (last: ${sample:-none})"
        sleep 1
    done
}

# Opens the demo's feed, `$1` rows long, with the service's player paused first. Every player requests
# audio focus while it plays, and focus is one token, so the feed's playing row would pause the
# service's player anyway; pausing it here makes that deterministic rather than a race. The leak hunt
# and the jank scenario both measure the feed from here.
open_feed() {
    adb_s shell cmd media_session dispatch pause >/dev/null
    wait_session_state "$SESSION_STATE_PAUSED" 30
    relaunch_demo --es com.superplayer.demo.extra.SCREEN FEED \
        --ei com.superplayer.demo.extra.FEED_ROWS "$1"
    # The relaunch returns once the Activity is drawn; the feed's first row then acquires, prepares and
    # starts a player over the network. Five seconds is that first start with room, so what follows
    # begins with a row playing rather than a row loading.
    sleep 5
    assert_demo_in_focus
}
