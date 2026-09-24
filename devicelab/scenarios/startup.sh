# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# Cold starts of the demo, STARTUP_LAUNCHES of them (default 20), in one trace that records what
# Perfetto's startup modules need: the platform's launch events, the main thread's scheduling, and
# every frame to the first. devicelab/startup/README.md is its manual — what counts as cold, why twenty
# in one trace, and how to read the report.
#
# Each launch: force-stop the demo, drop the kernel's page cache where the device allows it, start the
# Activity with `am start -W`, refuse the launch unless the platform says it was COLD, then wait for
# playback to advance before the next, so every startup runs to its end — first video frame, and the
# `reportFullyDrawn` the demo sends then — inside the trace.
#
# The trace starts once the harness's own launch is playing, so that launch — the first after an
# install, with its one-off verification and profile work — is a warm-up and not a sample. With
# `--trace-from launch` it is in the trace too, and the stdlib then finds one startup more than the
# scenario counted.

SCENARIO_DESCRIPTION="Cold-starts the demo STARTUP_LAUNCHES times (default 20) in one trace, for the startup metrics: launch events, main-thread scheduling, frames."
SCENARIO_TRACE_FROM=playing
# A bound, not a duration: the scenario ends the trace itself. Twenty launches take about three minutes
# on the API 36 emulator; this is room for a slow network's playback wait on every one of them.
SCENARIO_TRACE_MS=900000
# `frametimeline` as well as `startup`: Perfetto's `android_frames`, which TTID and TTFD are read from, is
# built by joining the app's frame slices to SurfaceFlinger's actual timeline, and without the timeline
# it is empty. The first capture found that out: every startup found, and no TTID for any of them.
SCENARIO_DATA_SOURCES=startup,frametimeline

# shellcheck source=../startup/startup.sh
. "$DEVICELAB_HOME/startup/startup.sh"

# Twenty, so that p95 by nearest rank is the 19th launch rather than the slowest: with ten it would be
# the maximum, and one launch that caught a background job would be the whole of the tail.
STARTUP_LAUNCHES="${STARTUP_LAUNCHES:-20}"
# Seconds between the force-stop and the launch, for the dying process's teardown to finish rather than
# overlap the next one's start.
STARTUP_SETTLE_S="${STARTUP_SETTLE_S:-2}"
# The bound on each launch's playback wait. Playback is a network fetch, so it is generous.
STARTUP_PLAYBACK_TIMEOUT_S="${STARTUP_PLAYBACK_TIMEOUT_S:-90}"

# --- Hooks -----------------------------------------------------------------------------------------

# Before a boot and a build are spent: the trace processor the report needs, downloaded and checked.
scenario_prepare() {
    trace_processor_bin >/dev/null
}

scenario_drive() {
    mkdir -p "$RUN_DIR/startup"
    : > "$RUN_DIR/startup/launches.tsv"
    startup_probe_drop_caches
    # Beside run.json rather than inside it, as devicelab/README.md asks of a consumer: whether these
    # were cold in the page cache's sense too, which a comparison between two runs has to know.
    printf '{"launches": %s, "page_cache_dropped": %s}\n' "$STARTUP_LAUNCHES" "$(json_bool "$STARTUP_CACHES_DROPPED")" \
        > "$RUN_DIR/startup/conditions.json"
    local launch=1
    while [ "$launch" -le "$STARTUP_LAUNCHES" ]; do
        startup_cold_launch "$launch"
        launch=$((launch + 1))
    done
}

scenario_report() {
    printf '%s cold launches, each after `am force-stop`' "$STARTUP_LAUNCHES"
    if [ "$STARTUP_CACHES_DROPPED" = 1 ]; then
        printf ' and with the page cache dropped, so every read at startup went to storage.\n\n'
    else
        printf '. **The page cache was not dropped** (no root on this device), so reads at startup may have been served from memory.\n\n'
    fi
    printf '### What the platform said (`am start -W`)\n\n'
    startup_launch_table "$RUN_DIR/startup/launches.tsv"
    printf '\n### What the trace says (`android.startup.startups`)\n\n'
    if [ ! -f "$RUN_DIR/trace.perfetto-trace" ]; then
        printf 'No trace.\n'
        return
    fi
    trace_processor_query "$RUN_DIR/trace.perfetto-trace" "$DEVICELAB_HOME/startup/sql/startups.sql" \
        "PACKAGE=$DEMO_PACKAGE" > "$RUN_DIR/startup/startups.csv"
    tp_csv_to_tsv < "$RUN_DIR/startup/startups.csv" | tsv_to_markdown
    printf '\nThe module found %s startups of the demo; the scenario made %s cold launches%s.\n' \
        "$(($(wc -l < "$RUN_DIR/startup/startups.csv") - 1))" "$STARTUP_LAUNCHES" \
        "$([ "$RUN_TRACE_FROM" = launch ] && printf ', and the harness one more before them')"
}

# --- The device ------------------------------------------------------------------------------------

# Whether the page cache can be dropped before each launch: root (`device_has_root`). Macrobenchmark drops it for
# a cold start whenever it can, because a start whose APK, dex and data files are still in memory is
# not the start a user who has not opened the app today gets, and a read at startup then costs a copy
# rather than an I/O.
#
# ref: https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/benchmark/benchmark-common/src/main/java/androidx/benchmark/Shell.kt (dropKernelPageCache)
# ref: https://docs.kernel.org/admin-guide/sysctl/vm.html#drop-caches
startup_probe_drop_caches() {
    STARTUP_CACHES_DROPPED=0
    ! device_has_root || STARTUP_CACHES_DROPPED=1
    [ "$STARTUP_CACHES_DROPPED" = 1 ] || log "warning: no root on this device, so the page cache is not dropped before a launch"
}

# One cold launch, numbered `$1`, appended to launches.tsv as launch, state, TotalTime, WaitTime.
startup_cold_launch() {
    local launch="$1" output result
    adb_s shell am force-stop "$DEMO_PACKAGE"
    if [ "$STARTUP_CACHES_DROPPED" = 1 ]; then
        adb_s shell "su 0 sh -c 'sync; echo 3 > /proc/sys/vm/drop_caches'" ||
            die "could not drop the page cache before launch $launch"
    fi
    sleep "$STARTUP_SETTLE_S"
    output="$(adb_s shell am start -W -n "$DEMO_PACKAGE/$DEMO_ACTIVITY")" ||
        die "launch $launch did not start: $output"
    result="$(printf '%s\n' "$output" | am_start_result)"
    [ -n "$result" ] || die "launch $launch did not complete: $(printf '%s' "$output" | tr -d '\r' | tr '\n' ' ')"
    # shellcheck disable=SC2086 # three words, split on purpose
    set -- $result
    [ "$1" = COLD ] || die "launch $launch was $1, not COLD: a process survived the force-stop"
    printf '%s\t%s\t%s\t%s\n' "$launch" "$1" "$2" "$3" >> "$RUN_DIR/startup/launches.tsv"
    wait_for_playback "$STARTUP_PLAYBACK_TIMEOUT_S" >/dev/null
    assert_demo_in_focus
    log "startup: launch $launch of $STARTUP_LAUNCHES, $1, TotalTime $2 ms"
}
