# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# The startup scenario's device-free half: reading what `am start -W` says about a launch, the
# percentiles taken over them, and the Markdown the report is written in. Pure functions over text,
# checked by test/selftest. README.md is the manual.

# Reads `am start -W`'s output on stdin and prints "<LaunchState> <TotalTime> <WaitTime>", or nothing
# when the launch did not complete (`Status:` other than `ok`) or a field is missing.
#
# TotalTime is the platform's own time to initial display for the launch — from the intent to the first
# frame of the launched Activity, as ActivityMetricsLogger measures it — and WaitTime adds the time
# `am` spent getting the intent to the system. LaunchState is COLD only when a process had to be
# started, which is how the scenario proves each launch it counts was one.
#
# ref: https://developer.android.com/topic/performance/vitals/launch-time#time-initial
# ref: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java (the fields printed)
am_start_result() {
    awk -F': ' '
        { sub(/\r$/, "") }
        $1 == "Status" { status = $2 }
        $1 == "LaunchState" { state = $2 }
        $1 == "TotalTime" { total = $2 }
        $1 == "WaitTime" { wait = $2 }
        END {
            if (status == "ok" && state != "" && total ~ /^[0-9]+$/ && wait ~ /^[0-9]+$/)
                print state, total, wait
        }
    '
}

# Prints the `$1`th percentile, by nearest rank, of the integers on stdin (one per line); nothing for no
# input. Nearest rank rather than interpolation, so every percentile reported is a launch that happened:
# with 20 launches, p95 is the 19th fastest and p50 the 10th.
#
# ref: https://en.wikipedia.org/wiki/Percentile#The_nearest-rank_method
nearest_rank() {
    sort -n | awk -v p="$1" '
        { v[NR] = $1 }
        END {
            if (NR == 0) exit
            rank = p / 100 * NR
            rank = (rank == int(rank)) ? rank : int(rank) + 1
            if (rank < 1) rank = 1
            print v[rank]
        }
    '
}

# Prints the report's launch table from `launches.tsv` (`$1`): one row per launch, then p50, p95 and
# the spread, all of TotalTime.
startup_launch_table() {
    local tsv="$1" totals
    printf '| launch | state | TotalTime (ms) | WaitTime (ms) |\n| ---: | --- | ---: | ---: |\n'
    awk -F'\t' '{ printf "| %s | %s | %s | %s |\n", $1, $2, $3, $4 }' "$tsv"
    totals="$(cut -f3 "$tsv")"
    printf '\nTotalTime over %s cold launches: p50 **%s ms**, p95 **%s ms**, min %s ms, max %s ms.\n' \
        "$(printf '%s\n' "$totals" | grep -c .)" \
        "$(printf '%s\n' "$totals" | nearest_rank 50)" "$(printf '%s\n' "$totals" | nearest_rank 95)" \
        "$(printf '%s\n' "$totals" | nearest_rank 0)" "$(printf '%s\n' "$totals" | nearest_rank 100)"
}

# Prints trace processor's CSV (`$1`, with a header row) as a Markdown table.
csv_to_markdown() {
    awk -F',' '
        { gsub(/"/, "") }
        NR == 1 {
            line = "|"; rule = "|"
            for (i = 1; i <= NF; i++) { line = line " " $i " |"; rule = rule " --- |" }
            print line; print rule; next
        }
        {
            line = "|"
            for (i = 1; i <= NF; i++) line = line " " $i " |"
            print line
        }
    ' "$1"
}
