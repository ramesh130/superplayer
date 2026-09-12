# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# The leak hunt's analysis: reading what the platform says is alive, comparing two heap dumps, and
# writing what grew and what retains it as Markdown. Sourced by scenarios/leak-hunt.sh; README.md
# beside this file is the manual.
#
# Everything above "Analysis" is a pure function over text and is pinned by test/selftest. What is
# below it runs the trace processor over the run's traces.

LEAK_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- What the platform says is alive ---------------------------------------------------------------

# Reads `dumpsys activity activities` on stdin; prints how many of package `$1`'s activities are in
# the task history. Those are the live ones: a destroyed Activity has no record, whatever still holds
# its object.
live_activity_count() {
    awk -v prefix="$1/" '
        /^[[:space:]]*\* Hist[[:space:]]+#[0-9]+: ActivityRecord\{/ {
            for (i = 1; i <= NF; i++) if (index($i, prefix) == 1) { n++; break }
        }
        END { print n + 0 }
    '
}

# Reads `dumpsys meminfo <package>` on stdin; prints "<java heap> <native heap> <graphics> <total PSS>
# <total RSS>" in KB from its App Summary, or nothing when the process was not running.
meminfo_summary() {
    awk '
        /App Summary/ { inside = 1; next }
        inside && /^[[:space:]]*Java Heap:/ { java = $3 }
        inside && /^[[:space:]]*Native Heap:/ { native = $3 }
        inside && /^[[:space:]]*Graphics:/ { graphics = $2 }
        inside && /^[[:space:]]*TOTAL PSS:/ { pss = $3; rss = $6; found = 1; exit }
        END { if (found) print java, native, graphics, pss, rss }
    '
}

# --- Who else holds the demo's Binder objects ------------------------------------------------------

# Reads a process's binder log (`/dev/binderfs/binder_logs/proc/<pid>`) on stdin; prints each pid
# holding a reference to one of its nodes, once, in numeric order. 0 is the kernel's, not a process.
# Each of the process's nodes is a line "  node <id>: … proc <pid> <pid> …", the pids being those of
# the processes holding a reference to it.
#
# ref: https://docs.kernel.org/admin-guide/binderfs.html (binder_logs)
# ref: https://github.com/torvalds/linux/blob/master/drivers/android/binder.c (print_binder_node_nilocked)
binder_ref_holders() {
    awk '/^  node [0-9]+:/ { if (sub(/.* proc /, "")) for (i = 1; i <= NF; i++) if ($i != 0) print $i }' |
        sort -n -u
}

# Reads `ps -A -o PID,PPID,NAME` on stdin; prints the pids whose parent is one of the zygotes `$@`.
# Those, and only those, are ART processes: every app and system_server is forked from a zygote,
# which has already started the runtime. That is what makes SIGUSR1 a request to collect rather than
# a signal that terminates.
#
# ref: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/os/ZygoteInit.java
zygote_children() {
    awk -v zygotes=" $* " 'NR > 1 && index(zygotes, " " $2 " ") { print $1 }'
}

# The processes a settle collects before the demo: the ART processes holding references to the Binder
# nodes of process `$1`, the zygotes being `$2`, from its binder log `$3` and `ps` output `$4`.
art_ref_holders() {
    # shellcheck disable=SC2086 # $2 is a list of pids, split on purpose
    awk -v demo="$1" 'NR == FNR { art[$1] = 1; next } ($1 in art) && $1 != demo' \
        <(zygote_children $2 < "$4") <(binder_ref_holders < "$3")
}

# --- Trace processor output ------------------------------------------------------------------------

# Trace processor's CSV — strings quoted, a quote doubled — on stdin, as tab-separated fields. Blank
# lines, which separate one result from the next, are dropped.
tp_csv_to_tsv() {
    perl -ne '
        chomp; s/\r$//;
        next if $_ eq "";
        my ($rest, @fields) = ($_);
        while (length $rest) {
            if ($rest =~ s/^"((?:[^"]|"")*)"(?:,|$)//) { (my $v = $1) =~ s/""/"/g; push @fields, $v }
            elsif ($rest =~ s/^([^,]*)(?:,|$)//) { push @fields, $1 }
        }
        print join("\t", @fields), "\n";
    '
}

# Tab-separated rows on stdin, the first of them the header, as a Markdown table.
tsv_to_markdown() {
    perl -ne '
        chomp;
        my @cells = map { s/\|/\\|/gr } split /\t/, $_, -1;
        print "| ", join(" | ", @cells), " |\n";
        print "|", " --- |" x @cells, "\n" if $. == 1;
    '
}

# --- Comparing two heap dumps ----------------------------------------------------------------------

# The classes whose reachable instance count differs between histogram `$1` (the baseline) and `$2`
# (the final dump), both as histogram.sql writes them. Tab-separated, with a header, largest growth
# first:
#
#   class  baseline  final  delta  final_bytes  owner  activity
#
# `owner` is `superplayer` for this project's classes (the library's and the demo's), `media3` for
# Media3's, and `-` for everything else, which is shown and not judged (heap_findings says why).
heap_diff() {
    printf 'class\tbaseline\tfinal\tdelta\tfinal_bytes\towner\tactivity\n'
    awk -F'\t' -v OFS='\t' '
        FNR == 1 { file++; next }
        file == 1 { before[$1] = $2; activity[$1] = $4; next }
        { after[$1] = $2; bytes[$1] = $3; activity[$1] = $4 }
        END {
            for (c in before) if (!(c in after)) { after[c] = 0; bytes[c] = 0 }
            for (c in after) {
                delta = after[c] - before[c]
                if (delta == 0) continue
                owner = c ~ /^com\.superplayer\./ ? "superplayer" : c ~ /^androidx\.media3\./ ? "media3" : "-"
                print c, before[c] + 0, after[c], delta, bytes[c] + 0, owner, activity[c] + 0
            }
        }
    ' <(tp_csv_to_tsv < "$1") <(tp_csv_to_tsv < "$2") | LC_ALL=C sort -t "$(printf '\t')" -k4,4nr -k1,1
}

# The findings in diff `$1`: "<class>\t<growth>" for every class of this project's that grew by at
# least `$2` instances, every Media3 class that grew by at least `$3`, and every Activity class that
# grew at all.
#
# Judged classes are the ones this project, its demo or Media3 owns, and Activities. A leak that is
# SuperPlayer's has to hold at least one object of such a class — the chain from a root to what it
# retains passes through code this repository wrote or wraps — while a JDK or framework class on its
# own moves with caches that are no part of this code's behaviour: interned strings, Compose's
# snapshot records, a connection pool. Those are shown, and not judged.
#
# Two thresholds, because the two kinds of class carry different noise. This project's classes are
# counted by what exists — players, rows, listeners — and two dumps of the same state agree on them to
# within a row. Media3's also carry what each live player has loaded: playlist segments, buffered
# allocations, formats. Those differ by a player's worth between two dumps of a healthy feed, while a
# Media3 leak retains a player's worth per repetition.
heap_findings() {
    awk -F'\t' -v OFS='\t' -v own="$2" -v media3="$3" '
        NR == 1 { next }
        ($7 == 1 && $4 >= 1) || ($6 == "superplayer" && $4 >= own) || ($6 == "media3" && $4 >= media3) { print $1, $4 }
    ' "$1"
}

# How many Activity instances histogram `$1` holds beyond the `$2` the platform lists as alive.
activity_excess() {
    tp_csv_to_tsv < "$1" | awk -F'\t' -v live="$2" '
        NR > 1 && $4 == 1 { held += $2 }
        END { excess = held - live; print (excess > 0 ? excess : 0) }
    '
}

# --- The report's Markdown -------------------------------------------------------------------------

# paths.sql's rows, as tab-separated fields on stdin, rendered as one block per class and path: the
# GC root first, then at each hop the field that points onward and the class of what it points to.
render_paths() {
    awk -F'\t' '
        NR == 1 { next }
        $1 != class {
            if (open) print "```"
            open = 0
            if (class != "") print ""
            class = $1; rank = ""
            printf "**`%s`: %s reachable in the final heap.**\n", $1, $2
        }
        $3 != rank {
            if (open) print "```"
            rank = $3
            printf "\nPath %s, shared by %s of them:\n\n```text\n", $3, $4
            open = 1
        }
        $5 == 0 { printf "%s  %s\n", $8, $6; next }
        { printf "  %s -> %s\n", ($7 == "" ? "[element]" : "." $7), $6 }
        END { if (open) print "```" }
    '
}

# native_callsites.sql's rows, as tab-separated fields on stdin: each callsite's unreleased bytes and
# allocation count, then its frames from the allocating one outward, each with its library.
render_native() {
    awk -F'\t' '
        function size(b) {
            if (b >= 1048576) return sprintf("%.1f MiB", b / 1048576)
            if (b >= 1024) return sprintf("%.1f KiB", b / 1024)
            return b " B"
        }
        NR == 1 { next }
        $1 != rank {
            if (open) print "   ```"
            rank = $1
            printf "%s%s. **%s** unreleased, %s allocations:\n\n   ```text\n", (open ? "\n" : ""), $1, size($2), $3
            open = 1
        }
        {
            library = $6; sub(/.*\//, "", library)
            printf "   %s%s\n", $5, (library == "" ? "" : "  (" library ")")
        }
        END { if (open) print "   ```" }
    '
}

# --- Analysis ------------------------------------------------------------------------------------
#
# From here on the functions run the trace processor over a run's traces, and write into $RUN_DIR.

LEAK_PATHS_PER_CLASS="${LEAK_PATHS_PER_CLASS:-3}"
LEAK_PATH_CLASSES="${LEAK_PATH_CLASSES:-8}"
LEAK_NATIVE_TOP="${LEAK_NATIVE_TOP:-8}"
LEAK_NATIVE_FRAMES="${LEAK_NATIVE_FRAMES:-14}"

# Fails unless trace `$1` holds one finished heap dump of the demo. A dump cut short reads as a heap
# with most of its objects missing, which a comparison would take for a spectacular non-leak.
leak_check_heap() {
    local row objects dumps processes unfinished process
    row="$(trace_processor_query "$1" "$LEAK_HOME/sql/heap_check.sql" | tp_csv_to_tsv | sed -n 2p)"
    IFS="$(printf '\t')" read -r objects _ dumps processes unfinished process <<EOF
$row
EOF
    [ "${objects:-0}" -gt 0 ] || die "$(basename "$1") holds no heap dump; raise LEAK_HEAP_DUMP_MS"
    [ "$dumps" = 1 ] && [ "$processes" = 1 ] || die "$(basename "$1") holds $dumps dumps of $processes processes, not one of the demo"
    [ "$unfinished" = 0 ] || die "$(basename "$1") holds a heap dump that did not finish; raise LEAK_HEAP_DUMP_MS"
    [ "$process" = "$DEMO_PACKAGE" ] || die "$(basename "$1") is a dump of '$process', not of $DEMO_PACKAGE"
}

# The growth, in instances, that counts as a finding in phase `$1`, as "<this project's> <Media3's>".
#
#   lifecycle  one instance per cycle, for both. Every cycle ends where it began, so anything a cycle
#              leaks it leaks at least once per cycle, and the dumps are taken with the player stopped,
#              so no class carries loaded state that differs between them.
#   feed       40 for this project's classes and 1200 for Media3's, from measured noise rather than
#              from the workload. Three clean feeds moved this project's classes by +1, +21 and 0 (the
#              +21 being one RevalidatingDataSource per data source the live players had open), and
#              Media3's by whole HLS playlists of the live players' state, 202 segments each, up to
#              +606. Each threshold is about twice its largest. The workload is what gives a leak room above them: a pass
#              sends about 23 players through the pool on the API 36 emulator, so a leak of one object
#              per recycle grows by about 23 a pass, and the default six passes put it near 140.
#
#              The first version derived these from row visits — 80 and 800 for two passes — on the
#              assumption that every row scrolled past takes a player. The negative test disproved it:
#              the listeners resetForReuse kept grew by +46, under 80. Most rows scrolled past find the
#              pool at its bound and take no player, which is the pool's contract.
#
# README.md gives the measurements these were set against.
leak_threshold() {
    case "$1" in
        lifecycle) printf '%s %s\n' "${LEAK_LIFECYCLE_THRESHOLD:-$LEAK_CYCLES}" "${LEAK_LIFECYCLE_MEDIA3_THRESHOLD:-$LEAK_CYCLES}" ;;
        feed) printf '%s %s\n' "${LEAK_FEED_THRESHOLD:-40}" "${LEAK_FEED_MEDIA3_THRESHOLD:-1200}" ;;
    esac
}

# Compares phase `$1`'s two dumps, appends its findings to findings.tsv, and prints its section.
leak_phase_report() {
    local phase="$1" title="$2" workload="$3" dir="$RUN_DIR/leak" own_threshold media3_threshold baseline_pid final_pid
    local live excess classes ignored capture
    read -r own_threshold media3_threshold <<EOF
$(leak_threshold "$phase")
EOF
    ignored="$(known_fields_sql < "$LEAK_HOME/known-retentions.tsv")"
    for capture in baseline final; do
        trace_processor_query "$RUN_DIR/heap-$phase-$capture.perfetto-trace" "$LEAK_HOME/sql/histogram.sql" \
            "IGNORED_FIELDS=$ignored" > "$dir/$phase-$capture.histogram.csv"
        trace_processor_query "$RUN_DIR/heap-$phase-$capture.perfetto-trace" "$LEAK_HOME/sql/set_aside.sql" \
            "IGNORED_FIELDS=$ignored" > "$dir/$phase-$capture.set-aside.csv"
    done
    heap_diff "$dir/$phase-baseline.histogram.csv" "$dir/$phase-final.histogram.csv" > "$dir/$phase.diff.tsv"
    heap_findings "$dir/$phase.diff.tsv" "$own_threshold" "$media3_threshold" > "$dir/$phase.findings.tsv"

    live="$(cat "$dir/$phase-final.live-activities")"
    excess="$(activity_excess "$dir/$phase-final.histogram.csv" "$live")"
    if [ "$excess" -gt 0 ]; then
        printf 'Activities (the system lists %s)\t%s\n' "$live" "$excess" >> "$dir/$phase.findings.tsv"
    fi
    sed "s/^/$phase\t/" "$dir/$phase.findings.tsv" >> "$dir/findings.tsv"

    baseline_pid="$(cat "$dir/$phase-baseline.pid")"
    final_pid="$(cat "$dir/$phase-final.pid")"

    printf '### %s\n\n%s\n\n' "$title" "$workload"
    [ "$baseline_pid" = "$final_pid" ] ||
        printf '**The demo was a different process at the final dump (pid %s, then %s). The comparison below is meaningless: a new process starts with an empty heap.**\n\n' "$baseline_pid" "$final_pid"
    printf 'Thresholds: a class of this project'"'"'s (`com.superplayer.*`) that grew by **%s** or more instances, a Media3 class (`androidx.media3.*`) by **%s** or more, and any Activity the heap holds beyond those the system lists. ' "$own_threshold" "$media3_threshold"
    printf 'The heap holds %s Activity instance(s) at the final dump; the system lists %s alive.\n\n' \
        "$(tp_csv_to_tsv < "$dir/$phase-final.histogram.csv" | awk -F'\t' 'NR > 1 && $4 == 1 { n += $2 } END { print n + 0 }')" "$live"

    if [ -s "$dir/$phase.findings.tsv" ]; then
        printf '**Findings: %s.**\n\n' "$(wc -l < "$dir/$phase.findings.tsv" | tr -d ' ')"
        { printf 'class\tbaseline\tfinal\tgrowth\tfinal bytes\n'
          awk -F'\t' -v OFS='\t' 'NR > 1 && $6 != "-"' "$dir/$phase.diff.tsv" |
              awk -F'\t' -v OFS='\t' 'NR == FNR { flagged[$1] = 1; next } ($1 in flagged) { print $1, $2, $3, "+" $4, $5 }' \
                  "$dir/$phase.findings.tsv" -
        } | tsv_to_markdown
        printf '\n#### What retains them\n\n'
        classes="$(leak_path_classes "$dir/$phase.findings.tsv" "$dir/$phase-final.histogram.csv")"
        trace_processor_query "$RUN_DIR/heap-$phase-final.perfetto-trace" "$LEAK_HOME/sql/paths.sql" \
            "CLASSES=$classes" "PATHS_PER_CLASS=$LEAK_PATHS_PER_CLASS" "IGNORED_FIELDS=$ignored" |
            tp_csv_to_tsv > "$dir/$phase.paths.tsv"
        render_paths < "$dir/$phase.paths.tsv"
        printf '\n'
    else
        printf '**No findings.** No class of this project'"'"'s grew by %s or more, no Media3 class by %s or more, and no Activity outlived its record.\n\n' "$own_threshold" "$media3_threshold"
    fi

    leak_set_aside_report "$phase"

    printf '#### Everything else that grew, shown and not judged\n\n'
    { printf 'class\tbaseline\tfinal\tgrowth\n'
      # A limit inside awk rather than `| head`: head would exit with thousands of rows unread, awk would
      # die of SIGPIPE, and under pipefail that fails the report without a word.
      awk -F'\t' -v OFS='\t' 'NR > 1 && $6 == "-" && $4 > 0 && n++ < 12 { print $1, $2, $3, "+" $4 }' "$dir/$phase.diff.tsv"
    } | tsv_to_markdown
    printf '\n'

    leak_native_report "$phase"
}

# Phase `$1`'s known retentions: how many objects each dump held only through a reference in
# known-retentions.tsv, which the counts above left out, and the classes the most of them were.
leak_set_aside_report() {
    local dir="$RUN_DIR/leak" before after
    grep -qv '^#' "$LEAK_HOME/known-retentions.tsv" 2>/dev/null || return 0
    before="$(tp_csv_to_tsv < "$dir/$1-baseline.set-aside.csv" | awk -F'\t' 'NR > 1 { n += $2 } END { print n + 0 }')"
    after="$(tp_csv_to_tsv < "$dir/$1-final.set-aside.csv" | awk -F'\t' 'NR > 1 { n += $2 } END { print n + 0 }')"
    printf '#### Known retentions, set aside\n\n'
    printf 'Objects held only through a reference in `devicelab/leak/known-retentions.tsv`, left out of every count above: **%s** at the baseline, **%s** at the final dump. Each entry says why it is not this code'"'"'s, and where it is tracked:\n\n' "$before" "$after"
    awk -F'\t' '/^#/ || NF == 0 { next } { printf "- `%s` (%s): %s\n", $1, $2, $3 }' "$LEAK_HOME/known-retentions.tsv"
    printf '\n'
    { printf 'class set aside at the final dump\tinstances\n'; tp_csv_to_tsv < "$dir/$1-final.set-aside.csv" | sed -n '2,7p'; } |
        tsv_to_markdown
    printf '\n'
}

# The classes to find paths for, as a SQL list: the findings' classes, most growth first and at most
# LEAK_PATH_CLASSES of them, plus every Activity class when Activities outlived their records.
leak_path_classes() {
    {
        awk -F'\t' -v limit="$LEAK_PATH_CLASSES" '$1 !~ /^Activities / && n++ < limit { print $1 }' "$1"
        if grep -q '^Activities ' "$1"; then
            tp_csv_to_tsv < "$2" | awk -F'\t' 'NR > 1 && $4 == 1 { print $1 }'
        fi
    } | sort -u | sed "s/'/''/g; s/.*/'&'/" | tr '\n' ',' | sed 's/,$//'
}

# Phase `$1`'s native profile: what was allocated during it and not freed by its end.
leak_native_report() {
    local trace="$RUN_DIR/native-$1.perfetto-trace" row unreleased allocated callsites problems
    printf '#### Native allocations not freed over the phase (heapprofd)\n\n'
    row="$(trace_processor_query "$trace" "$LEAK_HOME/sql/native_summary.sql" | tp_csv_to_tsv | sed -n 2p)"
    IFS="$(printf '\t')" read -r unreleased allocated callsites problems <<EOF
$row
EOF
    printf 'Estimated from samples: %s allocated over the phase, **%s** of it not freed by the end, across %s callsites.' \
        "$(leak_bytes "$allocated")" "$(leak_bytes "$unreleased")" "$callsites"
    if [ -n "$problems" ]; then
        printf ' **The profile is incomplete:** `%s`.' "$problems"
    fi
    printf ' Not judged; the largest, with their callstacks from the allocating frame outward:\n\n'
    trace_processor_query "$trace" "$LEAK_HOME/sql/native_callsites.sql" \
        "TOP=$LEAK_NATIVE_TOP" "FRAMES=$LEAK_NATIVE_FRAMES" | tp_csv_to_tsv | render_native
    printf '\n'
}

leak_bytes() {
    awk -v b="$1" 'BEGIN {
        if (b >= 1048576) printf "%.1f MiB", b / 1048576
        else if (b >= 1024) printf "%.1f KiB", b / 1024
        else printf "%d B", b
    }'
}

# The whole report: a verdict, memory at every capture, then each phase. Written to leak-report.md
# and printed, which is how it reaches report.md's scenario section as well.
leak_report() {
    local dir="$RUN_DIR/leak" capture
    : > "$dir/findings.tsv"
    {
        leak_phase_report lifecycle "Lifecycle" \
            "$LEAK_CYCLES cycles, each: rotate to landscape and back mid-playback; background with the session live and return; name each stream by content id through the session (MediaRequestResolver); background, pause, stop the service (releasing the session and its player) and return to a new one; enter the feed, scroll ten swipes and leave. Compared on the player screen before and after, with the service's player stopped through its session for each dump, following one untimed warm-up cycle."
        leak_phase_report feed "Feed" \
            "$LEAK_FEED_PASSES passes over a ${LEAK_FEED_ROWS}-row feed, each ${LEAK_FEED_SWIPES:-?} swipes down to the end and as many back to the top. Compared at the top of the feed before and after, following one untimed warm-up pass. Screenshots at the bottom: \`leak/feed-bottom-*.png\`."
    } > "$dir/phases.md"

    if [ -s "$dir/findings.tsv" ]; then
        printf '### Verdict: **leak** — %s finding(s)\n\n' "$(wc -l < "$dir/findings.tsv" | tr -d ' ')"
        { printf 'phase\tclass\tgrowth\n'; awk -F'\t' -v OFS='\t' '{ print $1, $2, "+" $3 }' "$dir/findings.tsv"; } | tsv_to_markdown
        printf '\n'
    else
        printf '### Verdict: **no leak found**\n\nIn what this scenario exercises. `devicelab/leak/README.md` lists what it does not.\n\n'
    fi

    if [ "${LEAK_GC_FORCED:-0}" = 1 ]; then
        printf 'GC forced before each dump: yes. First in the other processes holding references to the demo'"'"'s Binder objects — %s — so that a released session'"'"'s stub is not kept alive by a proxy they had not yet collected; then twice in the demo.\n\n' \
            "$(awk '{ print $2 }' "$dir/remote-gc.txt" 2>/dev/null | sort -u | tr '\n' ',' |
                sed 's/,$//; s/,/, /g; s/^$/none held one/')"
    else
        printf 'GC forced before each dump: no — no root on this device. Only reachable objects are counted either way, but a released session'"'"'s stub may still be held by another process'"'"'s proxy.\n\n'
    fi

    printf '### Memory at each capture\n\n`dumpsys meminfo`, KB. The phases are compared at the same point of the scenario, so a leak shows as growth between a baseline and its final.\n\n'
    { printf 'capture\tJava heap\tnative heap\tgraphics\ttotal PSS\ttotal RSS\n'
      for capture in lifecycle-baseline lifecycle-final feed-baseline feed-final; do
          printf '%s\t%s\n' "$capture" "$(meminfo_summary < "$dir/$capture.meminfo.txt" | tr ' ' '\t')"
      done
    } | tsv_to_markdown
    printf '\n'

    cat "$dir/phases.md"

    cat <<'EOF'
### What each half can say

The **Java heap dumps** (`java_hprof`) record objects and the references between them. They answer
*what is retained, and through which fields*: every hop of a path above names a field with its
declaring class, which is a file to open. They do not record where an object was allocated, so no
line of code that *created* a leaked object is named here, and none was captured to be found.

The **native profiles** (`heapprofd`) are the reverse. A native allocation has no object graph to
walk, so they record where it was allocated: a callstack, sampled. They say nothing about what is
keeping it alive. Frames name a function and its library; a file and line would need the library's
unstripped binary, which is not available for a device image's system libraries.

| File | What it is |
| --- | --- |
| `heap-<phase>-<baseline\|final>.perfetto-trace` | the four Java heap dumps; open one at https://ui.perfetto.dev |
| `native-<phase>.perfetto-trace` | the two native profiles, one per phase |
| `trace.perfetto-trace` | the run's own trace: process memory, polled every second, over the whole scenario |
| `leak/*.histogram.csv`, `leak/*.diff.tsv` | reachable instances per class at each dump, and the difference |
| `leak/findings.tsv` | the verdict's findings, one per line; empty means none |
EOF
}

# known-retentions.tsv on stdin — "<field>\t<tracked>\t<why>", with comments and blank lines — as the
# SQL list of fields histogram.sql and paths.sql treat as weak. Never empty: `'(none)'` is a name no
# field has — field names hold no parentheses — which keeps `IN (...)` well formed. Not `''`, which is
# the name trace processor gives every array slot.
known_fields_sql() {
    local fields
    # `tr` and not `paste -sd, -`: on empty input BSD paste prints nothing and GNU paste prints an
    # empty line, so a fallback keyed on paste's output fires on a Mac and not on a Linux runner.
    fields="$(awk -F'\t' '/^#/ || NF == 0 { next } { print $1 }' | sed "s/'/''/g; s/.*/'&'/" |
        tr '\n' ',' | sed 's/,$//')"
    printf '%s\n' "${fields:-'(none)'}"
}
