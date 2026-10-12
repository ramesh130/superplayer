# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# The iOS half of the harness (#435, `docs/ios-plan.md` §6): the same run — a device, a fresh build, a
# confirmed playback, a trace and the metadata — through Apple's tools instead of the Android SDK's.
#
#   Android                         iOS
#   adb devices, boot an AVD        devicectl's listing; a simulator through simctl
#   publishToMavenLocal + Gradle    xcodebuild of ios/Demo, which resolves the package by path
#   dumpsys media_session           the demo's own position lines, read off its console
#   Perfetto                        xctrace, the Activity Monitor template over every process
#
# The first half of this file is pure functions over text — devicectl's and simctl's JSON, the demo's
# log, xctrace's exported XML — and `devicelab/test/selftest` holds them to fixtures. The second half
# talks to a device, and only a run reaches it.
#
# Signing is never committed — the demo's project sets no team (ios/Demo/README.md) — so a physical
# device needs SP_DEVELOPMENT_TEAM, passed to xcodebuild on its command line with
# -allowProvisioningUpdates, and a run that finds the team written into the project file afterwards
# fails rather than leave it there.
#
# Every bound below is a ceiling and not an expectation, as `ios/hunt-leaks`' are: a listing answers
# in a second or two and gets a minute, and a boot or an install over the network takes tens of seconds
# and gets five, so only something broken outlasts one.

IOS_BUNDLE_ID=com.superplayer.demo
IOS_PROCESS=SuperPlayerDemo
IOS_PROJECT=ios/Demo/SuperPlayerDemo.xcodeproj
IOS_SCHEME=SuperPlayerDemo

# --- Pure: what the tools list ---------------------------------------------------------------------

# Reads `xcrun devicectl list devices --json-output` on stdin and prints one line per iOS device a run
# could use, tab-separated: udid, reality (physical or simulated), name, marketing name, product type,
# OS version, OS build, and how it is reached (devicectl's `transportType`: `wired`, `localNetwork`,
# `sameMachine` for a simulator). A physical device is usable when it is paired, booted and in Developer Mode —
# devicectl lists a phone that is none of those, and installing to it fails minutes later, after the
# build — and a simulator when it is booted.
ios_usable_devices() {
    python3 -I -c '
import json, sys
listing = json.load(sys.stdin)
for d in listing.get("result", {}).get("devices", []):
    hw, dp, cp = d.get("hardwareProperties", {}), d.get("deviceProperties", {}), d.get("connectionProperties", {})
    if hw.get("platform") != "iOS":
        continue
    reality = hw.get("reality")
    if reality == "physical":
        usable = (cp.get("pairingState") == "paired" and dp.get("bootState") == "booted"
                  and dp.get("developerModeStatus") == "enabled")
    elif reality == "simulated":
        usable = dp.get("bootState") == "booted"
    else:
        usable = False
    if usable:
        print("\t".join(str(v or "") for v in (hw.get("udid"), reality, dp.get("name"), hw.get("marketingName"),
              hw.get("productType"), dp.get("osVersionNumber"), dp.get("osBuildUpdate"), cp.get("transportType"))))
'
}

# Reads `xcrun simctl list -j devices` on stdin and prints the UDID of the available iOS simulator
# named `$1`, or nothing. iOS only: a tvOS simulator of the same name is not a phone.
ios_simulator_named() {
    python3 -I -c '
import json, sys
name = sys.argv[1]
for runtime, devices in json.load(sys.stdin).get("devices", {}).items():
    if ".iOS-" not in runtime:
        continue
    for d in devices:
        if d.get("name") == name and d.get("isAvailable", True):
            print(d["udid"])
            sys.exit()
' "$1"
}

# --- Pure: the demo's log ---------------------------------------------------------------------------
#
# The demo logs its position every two seconds on a player screen (`content=<id> position=<ms>`,
# DemoPlayback.swift) and every second on the feed's watched row (`row=<n> contentId=<id>
# positionMs=<ms>`, FeedScreen.swift). That is the readiness signal Android reads off the platform's
# media session: iOS publishes no such state to a shell, so the demo says it itself. devicectl's
# console relays each line with a carriage return before its newline, which every reader drops first,
# since it would otherwise end the last field of every line.

# The demo's log on stdin → "<content> <position ms>" per position line, in order.
ios_positions() {
    tr -d '\r' | awk '{
        content = ""; position = ""
        for (i = 1; i <= NF; i++) {
            if ($i ~ /^content=/) content = substr($i, 9)
            else if ($i ~ /^contentId=/) content = substr($i, 11)
            else if ($i ~ /^position=[0-9]+$/) position = substr($i, 10)
            else if ($i ~ /^positionMs=[0-9]+$/) position = substr($i, 12)
        }
        if (content != "" && position != "") print content, position
    }'
}

# The demo's log on stdin → the position of the first line that is ahead of the line before it for the
# same content, or nothing. Two lines, as `wait_for_playback` asks of two session samples: a position
# that has risen is content advancing, not a player that reached ready and stalled.
ios_advancing_position() {
    ios_positions | awk '$1 == content && $2 + 0 > position + 0 { print $2; exit } { content = $1; position = $2 }'
}

# The frame-output screen's log on stdin → "<first position> <last position> <frames> <missing>
# <runs> <repeated> <dropped> <interval ms> <largest gap ms>@<ending at ms>" from its first and last
# `frames=` lines, or nothing; a field the line does not carry reads `-`. The counts are the last
# line's, and are the whole session's: `FrameTally` counts from the first frame.
ios_frame_reading() {
    tr -d '\r' | awk '/ content=.* frames=[0-9]/ {
        for (i = 1; i <= NF; i++) {
            split($i, kv, "=")
            if (kv[1] == "position") position = kv[2]
            else if (kv[1] == "frames") frames = kv[2]
            else if (kv[1] == "missing") missing = kv[2]
            else if (kv[1] == "runs") runs = kv[2]
            else if (kv[1] == "repeated") repeated = kv[2]
            else if (kv[1] == "dropped") dropped = kv[2]
            else if (kv[1] == "intervalMs") interval = kv[2]
            else if (kv[1] == "largestGapMs") gap = kv[2]
        }
        if (first == "") first = position
        last = position
    }
    END {
        if (first != "")
            print first, last, frames, missing, runs, repeated, (dropped == "" ? -1 : dropped),
                (interval == "" ? "-" : interval), (gap == "" ? "-" : gap)
    }'
}

# The frame-output screen's log on stdin → "<steady ms> <steady frames> <steady missing> <other ms>
# <other missing>": the session split into **steady** playback and the rest, between consecutive
# `frames=` lines.
#
# A stretch between two lines is steady when the position advanced by exactly the log's interval,
# `$1` ms (DemoPlayback's two seconds), and no rebuffer or seek event of the collector's lies between
# them. Anything else is a stall, a seek, or AVFoundation jumping its own clock back after one, and
# frames missing there are the network's to explain, not the frame output's: a starved item hands
# over late frames by skipping them, and an `AVPlayerLayer` on the same starved item would have shown
# no more. So the exit criterion is judged on steady playback, and the rest is reported beside it.
ios_frame_steady() {
    tr -d '\r' | awk -v interval="${1:-2000}" '
        /evt=(rebuffer_started|rebuffer_ended|seek_requested|seek_completed) / { disturbed = 1; next }
        / content=.* frames=[0-9]/ {
            for (i = 1; i <= NF; i++) {
                split($i, kv, "=")
                if (kv[1] == "position") position = kv[2]
                else if (kv[1] == "frames") frames = kv[2]
                else if (kv[1] == "missing") missing = kv[2]
            }
            if (seen) {
                span = position - last_position
                if (!disturbed && span == interval) {
                    steady_ms += span; steady_frames += frames - last_frames; steady_missing += missing - last_missing
                } else {
                    other_ms += (span > 0 ? span : 0); other_missing += missing - last_missing
                }
            }
            seen = 1; disturbed = 0
            last_position = position; last_frames = frames; last_missing = missing
        }
        END { if (seen) print steady_ms + 0, steady_frames + 0, steady_missing + 0, other_ms + 0, other_missing + 0 }
    '
}

# A player screen's log on stdin → "<first position> <last position> <dropped>" from its `dropped=`
# lines with no frame output, or nothing: the `AVPlayerLayer` control's own count, as AVFoundation's
# access log reports it.
ios_dropped_reading() {
    tr -d '\r' | awk '/ content=.* dropped=-?[0-9]/ && !/ frames=/ {
        for (i = 1; i <= NF; i++) {
            split($i, kv, "=")
            if (kv[1] == "position") position = kv[2]
            else if (kv[1] == "dropped") dropped = kv[2]
        }
        if (first == "") first = position
        last = position
    }
    END { if (first != "") print first, last, dropped }'
}

# The demo's log on stdin → one "<content id>\t<ttff ms>\t<boundary>" per first frame telemetry
# reported, from `OSLogSink`'s `evt=first_frame` lines (`docs/telemetry-schema.md`). The boundary is
# USER_INTENT where the feed declared intent, which is the only kind the feed's numbers are read from.
ios_first_frames() {
    tr -d '\r' | awk '/evt=first_frame / {
        cid = ""; ms = ""; from = ""
        for (i = 1; i <= NF; i++) {
            if ($i ~ /^cid="/) { cid = substr($i, 6); sub(/"$/, "", cid) }
            else if ($i ~ /^ttffMs=[0-9]+$/) ms = substr($i, 8)
            else if ($i ~ /^from=/) from = substr($i, 6)
        }
        if (cid != "" && ms != "") printf "%s\t%s\t%s\n", cid, ms, from
    }'
}

# --- Pure: a UI test's log ---------------------------------------------------------------------------
#
# A scenario that sets SCENARIO_IOS_UI_TEST runs an XCUITest rather than the demo (`ios_test_demo`),
# and what it reads is xcodebuild's log of that run: the outcome of each test case, and the blocks of
# observations the test printed between `--- moq-smoke begin ---` and `--- moq-smoke end ---`
# (`MoqLiveSessionSmokeTests`' `Observations`), which are the run's findings whatever the outcome.

# xcodebuild's test log on stdin → one "<test>\t<passed|failed|skipped>" per test case it finished,
# the test named as `Class.method` whichever of xcodebuild's two spellings carried it — `-[Module.Class
# method]` or `Class.method()` — and, where the runner never started a test, one "runner\t<why>".
ios_test_outcomes() {
    tr -d '\r' | python3 -I -c '
import re, sys
for line in sys.stdin:
    case = re.search(r"[Tt]est [Cc]ase \x27(?:-\[(?:[\w]+\.)?(\w+) (\w+)\]|(?:[\w]+\.)?(\w+)\.(\w+)\(\))\x27 (passed|failed|skipped)", line)
    if case:
        cls, method = case.group(1) or case.group(3), case.group(2) or case.group(4)
        print("%s.%s\t%s" % (cls, method, case.group(5)))
        continue
    broken = re.search(r"encountered an error \((.*)\)\s*$", line)
    if broken:
        print("runner\t%s" % broken.group(1))
'
}

# xcodebuild's test log on stdin → every observation block, markers included, each line cut back to
# start at its marker or its `key = value`: xcodebuild may prefix a runner's line with nothing or with a
# timestamp and the process, and the report wants the observation alone.
ios_observation_blocks() {
    tr -d '\r' | awk '
        /--- moq-smoke begin ---/ { inside = 1; print "--- moq-smoke begin ---"; next }
        /--- moq-smoke end ---/ { if (inside) print "--- moq-smoke end ---"; inside = 0; next }
        inside { print }
    '
}

# Observation blocks on stdin → the value of `$2` in the block whose `test` is `$1`, or nothing.
ios_observation() {
    awk -v test="$1" -v key="$2" '
        /--- moq-smoke begin ---/ { current = ""; next }
        {
            split_at = index($0, " = ")
            if (!split_at) next
            k = substr($0, 1, split_at - 1); v = substr($0, split_at + 3)
            if (k == "test") current = v
            else if (current == test && k == key) { print v; exit }
        }
    '
}

# --- Pure: xctrace's export ------------------------------------------------------------------------

# Reads the export of an Activity Monitor recording's `activity-monitor-process-live` table on stdin
# and prints "<ms since the recording started>\t<pid>\t<physical footprint bytes>" for every sample of
# a process named `$1`, or of every process the table holds when `$1` is `*` — a recording that
# launched the demo holds the demo alone, under its display name rather than its executable's.
#
# The footprint is the kernel's `phys_footprint`, the number jetsam kills an app on and the one Xcode's
# memory gauge shows. xctrace writes each distinct value once with an `id` and refers back to it with
# `ref`, and writes `<sentinel/>` in a column that has no value, so a column is found by its position in
# the schema and a value by resolving the reference.
#
# ref: https://developer.apple.com/documentation/xcode/gathering-information-about-memory-use
ios_footprint_series() {
    python3 -I -c '
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
root = ET.parse(sys.stdin).getroot()
schema = root.find(".//schema")
if schema is None:
    sys.exit()
columns = [c.findtext("mnemonic") for c in schema.findall("col")]
at = {name: i for i, name in enumerate(columns)}
for needed in ("start", "process", "memory-physical-footprint"):
    if needed not in at:
        sys.exit("the export has no column " + needed)
seen = {}
def value(element):
    for e in element.iter():
        if "id" in e.attrib:
            seen[e.get("id")] = e
    return seen.get(element.get("ref")) if "ref" in element.attrib else element
for row in root.iter("row"):
    cells = [value(c) for c in row]
    if len(cells) < len(columns):
        continue
    process = cells[at["process"]]
    label = process.get("fmt", "") if process is not None else ""
    named = re.match(r"^(.*) \((\d+)\)$", label)
    if not named or (want != "*" and named.group(1) != want):
        continue
    footprint = cells[at["memory-physical-footprint"]]
    start = cells[at["start"]]
    if footprint is None or footprint.tag == "sentinel" or start is None:
        continue
    print("%d\t%s\t%s" % (int(start.text) // 1000000, named.group(2), footprint.text))
' "$1"
}

# Reads `xctrace export --toc` on stdin and prints when the first run's recording started, in
# milliseconds since the epoch: what lines the footprint series up with the demo's log, whose lines
# carry wall-clock times.
ios_trace_start_ms() {
    python3 -I -c '
import sys, datetime, xml.etree.ElementTree as ET
start = ET.parse(sys.stdin).getroot().findtext(".//run/info/summary/start-date")
if start:
    print(int(datetime.datetime.fromisoformat(start).timestamp() * 1000))
'
}

# Reads the export of a recording's `os-log` table on stdin and prints the demo's and the library's
# lines as the console would have — "<local time> SuperPlayerDemo[<pid>:0] [<category>] <message>" —
# with the recording's start `$1` (`ios_trace_start_ms`) added to each line's time, so every reader
# above reads a recording as it reads a console.
#
# The message is rebuilt from its parts rather than read off the row's display text, which prints an
# integer with a thousands separator ("position=2,000"): literal text and strings as written, numbers
# as their raw value.
ios_oslog_lines() {
    python3 -I -c '
import datetime, re, sys, xml.etree.ElementTree as ET
start_ms = int(sys.argv[1])
root = ET.parse(sys.stdin).getroot()
schema = root.find(".//schema")
if schema is None:
    sys.exit()
columns = [c.findtext("mnemonic") for c in schema.findall("col")]
at = {name: i for i, name in enumerate(columns)}
seen = {}
def value(element):
    for e in element.iter():
        if "id" in e.attrib:
            seen[e.get("id")] = e
    return seen.get(element.get("ref")) if "ref" in element.attrib else element
for row in root.iter("row"):
    cells = [value(c) for c in row]
    if len(cells) < len(columns):
        continue
    subsystem = cells[at["subsystem"]]
    if subsystem is None or subsystem.get("fmt") not in ("com.superplayer.demo", "com.superplayer"):
        continue
    parts = []
    for part in cells[at["message"]]:
        part = value(part)
        if part.tag in ("narrative-text", "string"):
            parts.append(part.get("fmt", part.text or ""))
        else:
            parts.append(part.text if part.text is not None else part.get("fmt", ""))
    process = re.match(r"^.* \((\d+)\)$", cells[at["process"]].get("fmt", ""))
    moment = datetime.datetime.fromtimestamp((start_ms + int(cells[at["time"]].text) // 1000000) / 1000).astimezone()
    print("%s SuperPlayerDemo[%s:0] [%s] %s" % (moment.strftime("%Y-%m-%d %H:%M:%S.%f%z"),
          process.group(1) if process else 0, cells[at["category"]].get("fmt"), "".join(parts)))
' "$1"
}

# --- Pure: the feed's verdict ------------------------------------------------------------------------

# Prints the feed scenario's report from the demo's log `$1`, the footprint series `$2`
# (`ios_footprint_series`' output) and the recording's start `$3` (`ios_trace_start_ms`), and writes
# its verdict — `flat`, `growing` or `unknown` — to the file `$4`. `$5` is 1 when the recording ended
# before the run did, which makes the verdict `unknown` whatever the samples it holds say: a pass the
# recording left part-way is not a pass's spread.
#
# Memory is judged per pass, as `ios/hunt-leaks` and the Android leak hunt judge it: the first pass down
# the feed and back is the baseline, holding whatever a first scroll builds once and keeps — the pool
# grown to its bound, classes, caches — and a later pass is **flat** when its median footprint sits
# inside the first pass's spread, at or under its 90th percentile, and **growing** when it sits above
# it. A spread and never one sample, because the footprint at any moment is mostly how much the watched
# row has buffered, which a sample's timing decides. Fewer than two passes, or a pass with fewer than
# ten samples, is `unknown`, which is not a pass. Time to first frame is reported per pass the same
# way, by when each first frame arrived, so a feed whose starts slow down as it scrolls shows it.
#
# Percentiles are nearest-rank throughout, so every one reported is a sample that was taken.
# ref: https://en.wikipedia.org/wiki/Percentile#The_nearest-rank_method
ios_feed_report() {
    python3 -I - "$@" <<'PY'
import datetime, re, statistics, sys

log_path, series_path, trace_start_ms, verdict_path = sys.argv[1], sys.argv[2], int(sys.argv[3] or 0), sys.argv[4]
ended_early = len(sys.argv) > 5 and sys.argv[5] == "1"
# Activity Monitor samples a process about once a second (observed on the iPhone #435 ran on), so ten
# is ten seconds of a pass: fewer, and a pass's p90 is one sample.
MIN_SAMPLES = 10
mib = lambda n: n / 1048576

def when(line):
    stamp = re.match(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)([+-]\d{4})", line)
    if not stamp:
        return None
    moment = datetime.datetime.strptime(stamp.group(1) + stamp.group(2), "%Y-%m-%d %H:%M:%S.%f%z")
    return int(moment.timestamp() * 1000)

lines = open(log_path, errors="replace").read().splitlines()
started = finished = None
pass_ends = []
pid = None
ttff, watched, no_player, ttff_at = {}, set(), 0, []
for line in lines:
    if pid is None:
        process = re.search(r" SuperPlayerDemo\[(\d+):", line)
        pid = process.group(1) if process else None
    if "scroll started" in line:
        started = when(line)
    elif re.search(r"scroll pass=\d+ ended", line):
        pass_ends.append(when(line))
    elif "scroll finished" in line:
        finished = when(line)
    row = re.search(r"\] row=(\d+) contentId=", line)
    if row:
        watched.add(int(row.group(1)))
    if "no player: the pool is at its bound" in line:
        no_player += 1
    first = re.search(r'evt=first_frame .*?cid="([^"]*)".*?ttffMs=(\d+) from=(\w+)', line)
    if first:
        ttff.setdefault(first.group(1), []).append((int(first.group(2)), first.group(3)))
        if first.group(3) == "USER_INTENT":
            ttff_at.append((when(line), int(first.group(2))))

from_intent = sorted(ms for frames in ttff.values() for ms, boundary in frames if boundary == "USER_INTENT")
def rank(values, p):
    k = -(-p * len(values) // 100)
    return values[max(1, k) - 1]

def pass_of(t):
    if started is None or t is None or t < started:
        return "before the scroll"
    if finished is not None and t > finished:
        return "after the scroll"
    return sum(1 for e in pass_ends if e is not None and e < t) + 1

print("### Time to first frame\n")
print(f"- Rows that became the watched row: {len(watched)}")
print(f"- Rows that reached a first frame: {len(ttff)}; first frames reported: {sum(map(len, ttff.values()))}")
if from_intent:
    print(f"- From declared intent: median **{statistics.median(from_intent):.0f} ms**, p90 {rank(from_intent, 90)} ms, "
          f"max {from_intent[-1]} ms, n={len(from_intent)} — real time over a real CDN, a reading and not a claim")
print(f"- Watched rows that found the pool at its bound: {no_player}\n")
by_pass_ttff = {}
for t, ms in ttff_at:
    by_pass_ttff.setdefault(pass_of(t), []).append(ms)
if by_pass_ttff:
    print("| Pass | First frames from intent | Median | p90 | Max |")
    print("| --- | ---: | ---: | ---: | ---: |")
    for n, values in sorted(by_pass_ttff.items(), key=lambda kv: kv[0] if isinstance(kv[0], int) else (0 if kv[0].startswith("before") else 10**6)):
        q = sorted(values)
        print(f"| {n} | {len(q)} | {statistics.median(q):.0f} ms | {rank(q, 90)} ms | {q[-1]} ms |")
    print()

samples = []
for line in open(series_path).read().splitlines():
    ms, sample_pid, footprint = line.split("\t")
    if pid is None or sample_pid == pid:
        samples.append((trace_start_ms + int(ms), int(footprint)))

print("### Memory over the scroll\n")
verdict, by_pass = "unknown", {}
if started is None or not samples:
    print("No scroll was logged, or the recording holds no sample of the demo's process: nothing to judge.\n")
else:
    end = finished or (pass_ends[-1] if pass_ends else max(t for t, _ in samples))
    for t, footprint in samples:
        if started <= t <= end:
            n = sum(1 for e in pass_ends if e is not None and e < t) + 1
            by_pass.setdefault(n, []).append(mib(footprint))
    print("The demo's physical footprint as xctrace's Activity Monitor sampled it, per pass down the feed and")
    print("back. The first pass is the baseline; a later one is flat when its median is at or under the")
    print("first's p90.\n")
    if by_pass:
        print("| Pass | Samples | Median | p10 | p90 | Max |")
        print("| --- | ---: | ---: | ---: | ---: | ---: |")
    else:
        print(f"No sample of the demo's process falls inside the scroll ({len(samples)} outside it): the recording")
        print("did not cover it, and `run.json`'s `xctrace.end_reason` says why.")
    for n, values in sorted(by_pass.items()):
        q = sorted(values)
        print(f"| {n} | {len(q)} | {statistics.median(q):.1f} MiB | {rank(q, 10):.1f} MiB | "
              f"{rank(q, 90):.1f} MiB | {q[-1]:.1f} MiB |")
    print()
    passes = [values for _, values in sorted(by_pass.items())]
    if ended_early:
        print("Verdict: **unknown** — the recording ended before the run did, so no pass's spread is whole.\n")
    elif finished is None:
        print("Verdict: **unknown** — the scroll did not finish inside the recording, so its last pass is not whole.\n")
    elif len(passes) >= 2 and all(len(v) >= MIN_SAMPLES for v in passes):
        baseline = sorted(passes[0])
        ceiling = rank(baseline, 90)
        above = [statistics.median(v) for v in passes[1:] if statistics.median(v) > ceiling]
        verdict = "growing" if above else "flat"
        last = statistics.median(passes[-1])
        print(f"Verdict: **{verdict}** — the last pass's median is {last:.1f} MiB against the first pass's "
              f"median {statistics.median(baseline):.1f} MiB and p90 {ceiling:.1f} MiB "
              f"({last - statistics.median(baseline):+.1f} MiB).\n")
    else:
        print(f"Verdict: **unknown** — judging needs two passes of at least {MIN_SAMPLES} samples each.\n")
open(verdict_path, "w").write(verdict + "\n")
PY
}

# --- Talking to a device -----------------------------------------------------------------------------

ios_devices_listing() {
    local json="$DEVICELAB_WORK/devicectl-devices.json"
    with_timeout 60 xcrun devicectl list devices --json-output "$json" >/dev/null 2>&1 ||
        die "devicectl could not list devices (is Xcode installed and selected?)"
    cat "$json"
}

# Sets IOS_UDID, IOS_REALITY and the DEVICE_* the report and run.json read, for the device a run
# uses: the one --serial or SP_DEVICE names; else SP_SIMULATOR's, booted for the run if it is not;
# else the one physical device that is usable, else the one booted simulator. Two of a kind is a
# choice the run will not make, as on Android.
ios_ensure_device() {
    local usable line count named
    if [ -z "$SERIAL" ] && [ -n "${SP_SIMULATOR:-}" ]; then
        named="$(with_timeout 60 xcrun simctl list -j devices | ios_simulator_named "$SP_SIMULATOR")"
        [ -n "$named" ] || die "no iOS simulator named '$SP_SIMULATOR' (xcrun simctl create it first)"
        log "simulator $SP_SIMULATOR ($named), booted if it is not"
        with_timeout 300 xcrun simctl bootstatus "$named" -b >/dev/null || die "simulator $named did not boot"
        SERIAL="$named"
    fi
    usable="$(ios_devices_listing | ios_usable_devices)"
    if [ -n "$SERIAL" ]; then
        line="$(printf '%s\n' "$usable" | awk -F '\t' -v u="$SERIAL" '$1 == u')"
        [ -n "$line" ] ||
            die "$SERIAL is not a usable iOS device: devicectl does not list it as paired, booted and in Developer Mode (or as a booted simulator)"
    else
        line="$(printf '%s\n' "$usable" | awk -F '\t' '$2 == "physical"')"
        count="$(printf '%s' "$line" | grep -c . || true)"
        [ "$count" -le 1 ] || die "several iOS devices are usable; name one with --serial or SP_DEVICE"
        if [ "$count" = 0 ]; then
            line="$(printf '%s\n' "$usable" | awk -F '\t' '$2 == "simulated"')"
            count="$(printf '%s' "$line" | grep -c . || true)"
            [ "$count" -le 1 ] || die "several simulators are booted; name one with --serial or SP_DEVICE"
        fi
        [ "$count" = 1 ] ||
            die "no usable iOS device: attach a phone in Developer Mode, boot a simulator, or name one with SP_SIMULATOR"
    fi
    IFS=$'\t' read -r IOS_UDID IOS_REALITY DEVICE_NAME DEVICE_MODEL DEVICE_PRODUCT_TYPE DEVICE_OS_VERSION DEVICE_OS_BUILD \
        IOS_TRANSPORT <<<"$line"
    SERIAL="$IOS_UDID"
    DEVICE_MANUFACTURER=Apple
    DEVICE_IS_EMULATOR=0
    [ "$IOS_REALITY" = physical ] || DEVICE_IS_EMULATOR=1
}

# Builds the demo for the run's device, sets DEMO_APP to the .app and DEMO_APP_SHA256 to its
# executable's hash. Release for `--build-type benchmark` — the Android run's release-like build, and
# `ios/hunt-leaks`' choice for its reason: a Debug build allocates in ways no adopter's app does — and
# Debug for `debug`. The package is resolved **by path** in the same invocation, so there is no
# published artifact that could be stale and nothing for `lib/artifacts.sh` to check: the app is the
# tree. `$1` is the action, `build` by default; `build-for-testing` builds the scheme's UI tests and
# their runner beside the app, for `ios_test_demo`.
ios_build_demo() {
    local action="${1:-build}" configuration sdk before team_args=()
    case "$BUILD_TYPE" in
        benchmark) configuration=Release ;;
        debug) configuration=Debug ;;
    esac
    sdk=iphoneos
    if [ "$IOS_REALITY" = physical ]; then
        [ -n "${SP_DEVELOPMENT_TEAM:-}" ] ||
            die "a physical device needs SP_DEVELOPMENT_TEAM, the team to sign the demo with; it is never committed"
        team_args=(-allowProvisioningUpdates "DEVELOPMENT_TEAM=$SP_DEVELOPMENT_TEAM")
    else
        sdk=iphonesimulator
    fi
    before="$(tree_state)"
    SP_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD)"
    SP_TREE_DIRTY=0
    [ -z "$(git -C "$REPO_ROOT" status --porcelain)" ] || SP_TREE_DIRTY=1
    SP_TREE_NOTE=""
    [ "$SP_TREE_DIRTY" = 0 ] || SP_TREE_NOTE="plus uncommitted changes"
    DEMO_CONFIGURATION="$configuration"
    log "xcodebuild $action $IOS_SCHEME, $configuration, for $IOS_UDID (log: $RUN_DIR/build.log)"
    if ! (cd "$REPO_ROOT" && xcodebuild "$action" -quiet -project "$IOS_PROJECT" -scheme "$IOS_SCHEME" \
        -configuration "$configuration" -destination "id=$IOS_UDID" \
        -derivedDataPath "$REPO_ROOT/.build/devicelab-ios" ${team_args[@]+"${team_args[@]}"}) >>"$RUN_DIR/build.log" 2>&1; then
        tail -40 "$RUN_DIR/build.log" >&2
        die "xcodebuild failed; the whole log is $RUN_DIR/build.log"
    fi
    # Xcode may write the team it was handed into the project; a run never leaves one behind.
    [ "$(tree_state)" = "$before" ] ||
        die "the working tree changed during the build (a signing team written into $IOS_PROJECT?); revert it — signing is never committed"
    DEMO_APP="$REPO_ROOT/.build/devicelab-ios/Build/Products/$configuration-$sdk/$IOS_PROCESS.app"
    [ -d "$DEMO_APP" ] || die "the build produced no $DEMO_APP"
    DEMO_APP_SHA256="$(sha256_of "$DEMO_APP/$IOS_PROCESS")"
}

ios_install_demo() {
    if [ "$IOS_REALITY" = physical ]; then
        with_timeout 300 xcrun devicectl device install app --device "$IOS_UDID" "$DEMO_APP" >>"$RUN_DIR/build.log" 2>&1 ||
            die "devicectl could not install the demo (is the phone unlocked, and the developer trusted in Settings?)"
    else
        with_timeout 300 xcrun simctl install "$IOS_UDID" "$DEMO_APP" || die "simctl could not install the demo"
    fi
    log "installed the $DEMO_CONFIGURATION build"
}

# Runs the UI test `$1` (`Target/Class`) on the run's device from what `ios_build_demo
# build-for-testing` built, writing xcodebuild's log to `$RUN_DIR/test.log` (which IOS_LOG then
# names), its result bundle to `test.xcresult`, and what `ios_test_outcomes` and
# `ios_observation_blocks` read from the log to `outcomes.tsv` and `observations.txt`. Sets
# RUN_TEST_EXIT to xcodebuild's status and does **not** fail on it: a test that failed is a finding,
# and the scenario's verdict is where it is judged.
#
# xcodebuild installs the app and the test runner itself, so there is no `ios_install_demo` and no
# console: the runner's own output is the log. What reaches the runner from here is environment, each
# `NAME=value` of SCENARIO_IOS_TEST_ENV handed on as `TEST_RUNNER_NAME`, the prefix xcodebuild strips
# on the way in.
ios_test_demo() {
    local assignment env_args=()
    for assignment in ${SCENARIO_IOS_TEST_ENV[@]+"${SCENARIO_IOS_TEST_ENV[@]}"}; do
        env_args+=("TEST_RUNNER_$assignment")
    done
    IOS_LOG="$RUN_DIR/test.log"
    log "xcodebuild test-without-building $1 on $IOS_UDID (log: $IOS_LOG)"
    set +e
    # Bounded at fifteen minutes, a ceiling as every bound here is: the two tests take about a minute
    # against a relay that answers, and the runner's first install and launch take another.
    (cd "$REPO_ROOT" && with_timeout "${IOS_TEST_TIMEOUT:-900}" env ${env_args[@]+"${env_args[@]}"} \
        xcodebuild test-without-building -project "$IOS_PROJECT" -scheme "$IOS_SCHEME" \
        -configuration "$DEMO_CONFIGURATION" -destination "id=$IOS_UDID" \
        -derivedDataPath "$REPO_ROOT/.build/devicelab-ios" -only-testing:"$1" \
        -resultBundlePath "$RUN_DIR/test.xcresult") >"$IOS_LOG" 2>&1
    RUN_TEST_EXIT=$?
    set -e
    ios_test_outcomes <"$IOS_LOG" >"$RUN_DIR/outcomes.tsv"
    ios_observation_blocks <"$IOS_LOG" >"$RUN_DIR/observations.txt"
    log "xcodebuild exited $RUN_TEST_EXIT: $(cut -f2 "$RUN_DIR/outcomes.tsv" | sort | uniq -c | xargs)"
}

# Launches the demo with launch arguments `$2…` — `-SPDemoScreen <screen>` and whatever the screen
# reads — terminating any instance first, and streams its console to `$RUN_DIR/console-$1.log`, which
# `IOS_LOG` then names, until `ios_stop_demo`. Each launch keeps its own file, so a scenario that
# launches twice reads each launch's lines alone.
#
# The console is the log: OS_ACTIVITY_DT_MODE makes the process copy its own os_log lines to stderr,
# which devicectl's console relays, because `log collect` from a phone needs root and `log stream` does
# not reach one. The console session also owns the process: ending it ends the app.
ios_launch_demo() {
    ios_stop_demo
    IOS_LOG="$RUN_DIR/console-$1.log"
    : >"$IOS_LOG"
    shift
    if [ "$IOS_REALITY" = physical ]; then
        xcrun devicectl device process launch --device "$IOS_UDID" --terminate-existing --console \
            --environment-variables '{"OS_ACTIVITY_DT_MODE":"enable"}' "$IOS_BUNDLE_ID" -- "$@" >"$IOS_LOG" 2>&1 &
    else
        SIMCTL_CHILD_OS_ACTIVITY_DT_MODE=enable xcrun simctl launch --console-pty --terminate-running-process \
            "$IOS_UDID" "$IOS_BUNDLE_ID" "$@" >"$IOS_LOG" 2>&1 &
    fi
    IOS_CONSOLE_PID=$!
}

# Ends the console session, and with it the app. What it logged stays in its file.
ios_stop_demo() {
    [ -n "${IOS_CONSOLE_PID:-}" ] || return 0
    kill "$IOS_CONSOLE_PID" 2>/dev/null || true
    wait "$IOS_CONSOLE_PID" 2>/dev/null || true
    IOS_CONSOLE_PID=""
    [ "$IOS_REALITY" = physical ] || xcrun simctl terminate "$IOS_UDID" "$IOS_BUNDLE_ID" >/dev/null 2>&1 || true
}

# Waits until the demo's log shows a position advancing in lines written after the call; prints the
# position reached. After the call, so that a second wait — `smoke`'s, at its end — is evidence about
# now rather than about the launch. The console ending first is the app exiting — a crash, a refusal
# to launch — and fails the run at once.
ios_wait_for_playback() {
    local timeout="${1:-60}" deadline position from
    from=$(($(wc -l <"$IOS_LOG") + 1))
    deadline=$(($(date +%s) + timeout))
    while :; do
        position="$(tail -n "+$from" "$IOS_LOG" | ios_advancing_position)"
        if [ -n "$position" ]; then
            printf '%s\n' "$position"
            return 0
        fi
        kill -0 "$IOS_CONSOLE_PID" 2>/dev/null ||
            die "the demo's console ended before playback advanced: $(tail -3 "$IOS_LOG" | tr '\n' ' ')"
        [ "$(date +%s)" -lt "$deadline" ] || die "no advancing playback within ${timeout}s"
        sleep 2
    done
}

# Waits up to `$2` seconds for a line matching the extended regex `$1` in the demo's log.
ios_wait_for_log() {
    local deadline=$(($(date +%s) + $2))
    until grep -Eq "$1" "$IOS_LOG"; do
        kill -0 "$IOS_CONSOLE_PID" 2>/dev/null || die "the demo's console ended before '$1' was logged"
        [ "$(date +%s)" -lt "$deadline" ] || die "'$1' was not logged within $2 s"
        sleep 2
    done
}

# --- xctrace ---------------------------------------------------------------------------------------
#
# The run's trace is the Activity Monitor template over **every process**, not attached to the demo:
# an attach to a process devicectl's console launched is refused ("Cannot find process") — observed
# on iOS 27 with Xcode 27.1 — and a launch by xctrace would cost the console the log is read from. The
# demo's rows are picked out of the export by name and pid.

ios_trace_start() {
    IOS_TRACE="$RUN_DIR/trace.trace"
    xcrun xctrace record --template "Activity Monitor" --device "$IOS_UDID" --all-processes --no-prompt \
        --time-limit "$((TRACE_MS / 1000))s" --output "$IOS_TRACE" >"$RUN_DIR/xctrace.log" 2>&1 &
    IOS_TRACE_PID=$!
    log "recording xctrace (Activity Monitor, every process, bounded at ${TRACE_MS} ms)"
}

# Stops the recording — SIGINT, as Ctrl-C does — and waits for xctrace to write it; then exports the
# demo's footprint series. A recording xctrace ended on its own is recorded, with the reason it gave:
# the time limit, or "Device disconnected", which is a recording that covers less than the run.
ios_trace_stop() {
    RUN_TRACE_ENDED_BY_BOUND=0
    IOS_TRACE_ENDED_EARLY=1
    if kill -0 "$IOS_TRACE_PID" 2>/dev/null; then
        IOS_TRACE_ENDED_EARLY=0
        kill -INT "$IOS_TRACE_PID" 2>/dev/null || true
    fi
    wait "$IOS_TRACE_PID" 2>/dev/null || true
    IOS_TRACE_PID=""
    [ -d "$IOS_TRACE" ] || die "xctrace wrote no trace ($RUN_DIR/xctrace.log)"
    xcrun xctrace export --input "$IOS_TRACE" --toc >"$RUN_WORK/toc.xml" 2>/dev/null || die "xctrace could not read its own trace"
    IOS_TRACE_END_REASON="$(sed -n 's:.*<end-reason>\(.*\)</end-reason>.*:\1:p' "$RUN_WORK/toc.xml" | head -1)"
    IOS_TRACE_START_MS="$(ios_trace_start_ms <"$RUN_WORK/toc.xml")"
    case "$IOS_TRACE_END_REASON" in
        *"ime limit"*)
            RUN_TRACE_ENDED_BY_BOUND=1
            IOS_TRACE_ENDED_EARLY=0
            ;;
    esac
    [ "$IOS_TRACE_ENDED_EARLY" = 0 ] ||
        log "warning: xctrace stopped recording before the run did (${IOS_TRACE_END_REASON:-no reason given})"
    xcrun xctrace export --input "$IOS_TRACE" \
        --xpath '/trace-toc/run[@number="1"]/data/table[@schema="activity-monitor-process-live"]' \
        >"$RUN_WORK/process-live.xml" 2>/dev/null || die "xctrace could not export the process table"
    ios_footprint_series "$IOS_PROCESS" <"$RUN_WORK/process-live.xml" >"$RUN_DIR/footprint.tsv"
    log "xctrace ended: ${IOS_TRACE_END_REASON:-stopped}; $(grep -c . "$RUN_DIR/footprint.tsv" || true) footprint samples of the demo"
}

# The other way to record, for a scenario that needs the recording whole (`SCENARIO_IOS_RECORDED_LAUNCH`):
# xctrace launches the demo itself, with `$@` as its arguments, and records Activity Monitor on it and
# its own `os_log` lines (the `os_log` instrument) until `TRACE_MS` runs out. Over every process the
# recording ends after about a second on the iPhone #435 ran on — wired or over the network, its table
# of contents saying "Device disconnected" — while a recording of a launched process runs to its limit;
# observed on iOS 27.0 with Xcode 27.1, cause unknown. What it costs is the live console, so nothing is
# confirmed or waited on while it runs: the scenario must drive itself and know its own length, as the
# feed's `-SPFeedScroll` does, and the log is read back out of the recording afterwards
# (`ios_oslog_lines`) into `console-main.log`, where every reader finds it. Sets IOS_LOG and
# RUN_PLAYBACK_POSITION_MS, which is empty when no position advanced.
ios_record_launch() {
    IOS_TRACE="$RUN_DIR/trace.trace"
    IOS_LOG="$RUN_DIR/console-main.log"
    log "recording xctrace (Activity Monitor and os_log, launching the demo, for ${TRACE_MS} ms)"
    # Bounded beyond xctrace's own limit, for the time it takes to start and to write the trace.
    with_timeout $((TRACE_MS / 1000 + 300)) xcrun xctrace record --template "Activity Monitor" --instrument os_log \
        --device "$IOS_UDID" --no-prompt --time-limit "$((TRACE_MS / 1000))s" --output "$IOS_TRACE" \
        --launch -- "$IOS_BUNDLE_ID" "$@" >"$RUN_DIR/xctrace.log" 2>&1 || true
    [ -d "$IOS_TRACE" ] || die "xctrace wrote no trace ($RUN_DIR/xctrace.log)"
    xcrun xctrace export --input "$IOS_TRACE" --toc >"$RUN_WORK/toc.xml" 2>/dev/null || die "xctrace could not read its own trace"
    IOS_TRACE_END_REASON="$(sed -n 's:.*<end-reason>\(.*\)</end-reason>.*:\1:p' "$RUN_WORK/toc.xml" | head -1)"
    IOS_TRACE_START_MS="$(ios_trace_start_ms <"$RUN_WORK/toc.xml")"
    RUN_TRACE_ENDED_BY_BOUND=0
    IOS_TRACE_ENDED_EARLY=1
    # Here the limit is the plan rather than a bound hit: the run is as long as the recording.
    case "$IOS_TRACE_END_REASON" in *"ime limit"*) IOS_TRACE_ENDED_EARLY=0 ;; esac
    [ "$IOS_TRACE_ENDED_EARLY" = 0 ] ||
        log "warning: xctrace stopped recording before its limit (${IOS_TRACE_END_REASON:-no reason given})"
    xcrun xctrace export --input "$IOS_TRACE" \
        --xpath '/trace-toc/run[@number="1"]/data/table[@schema="os-log"]' >"$RUN_WORK/os-log.xml" 2>/dev/null ||
        die "xctrace could not export the log table"
    ios_oslog_lines "$IOS_TRACE_START_MS" <"$RUN_WORK/os-log.xml" >"$IOS_LOG"
    xcrun xctrace export --input "$IOS_TRACE" \
        --xpath '/trace-toc/run[@number="1"]/data/table[@schema="activity-monitor-process-live"]' \
        >"$RUN_WORK/process-live.xml" 2>/dev/null || die "xctrace could not export the process table"
    ios_footprint_series '*' <"$RUN_WORK/process-live.xml" >"$RUN_DIR/footprint.tsv"
    RUN_PLAYBACK_POSITION_MS="$(ios_advancing_position <"$IOS_LOG")"
    log "xctrace ended: ${IOS_TRACE_END_REASON:-stopped}; $(grep -c . "$IOS_LOG" || true) log lines, $(grep -c . "$RUN_DIR/footprint.tsv" || true) footprint samples"
}

# On any exit: the recording and the console, so neither outlives a failed run.
ios_on_exit() {
    [ -z "${IOS_TRACE_PID:-}" ] || kill -INT "$IOS_TRACE_PID" 2>/dev/null || true
    [ -z "${IOS_CONSOLE_PID:-}" ] || kill "$IOS_CONSOLE_PID" 2>/dev/null || true
    if declare -F scenario_cleanup >/dev/null; then scenario_cleanup || true; fi
}

# --- What a run records ----------------------------------------------------------------------------

# Prints run.json for an iOS run: `lib/metadata.sh`'s shape where a field means the same thing on
# both platforms, and fields of its own where it cannot — the app's executable hash in place of a
# published artifact list, the OS version and build in place of an SDK level, xctrace in place of
# Perfetto. `platform` says which shape a reader has.
ios_run_json() {
    cat <<JSON
{
  "schema": $RUN_METADATA_SCHEMA,
  "platform": "ios",
  "run_id": $(json_str "$RUN_ID"),
  "started_at": $(json_str "$RUN_STARTED_AT"),
  "finished_at": $(json_str "$RUN_FINISHED_AT"),
  "scenario": $(json_str "$RUN_SCENARIO"),
  "superplayer": {
    "commit": $(json_str "$SP_COMMIT"),
    "tree_dirty": $(json_bool "$SP_TREE_DIRTY"),
    "version": $(json_str "$SP_VERSION")
  },
  "demo": {
    "bundle_id": $(json_str "$IOS_BUNDLE_ID"),
    "configuration": $(json_str "$DEMO_CONFIGURATION"),
    "executable_sha256": $(json_str "$DEMO_APP_SHA256")
  },
  "device": {
    "udid": $(json_str "$IOS_UDID"),
    "kind": "phone",
    "simulator": $(json_bool "$DEVICE_IS_EMULATOR"),
    "name": $(json_str "$DEVICE_NAME"),
    "model": $(json_str "$DEVICE_MODEL"),
    "product_type": $(json_str "$DEVICE_PRODUCT_TYPE"),
    "os_version": $(json_str "$DEVICE_OS_VERSION"),
    "os_build": $(json_str "$DEVICE_OS_BUILD"),
    "transport": $(json_str "${IOS_TRANSPORT:-}")
  },
  "host": {
    "os": $(json_str "$HOST_OS"),
    "xcode": $(json_str "$HOST_XCODE"),
    "ci": $(json_bool "$HOST_CI")
  },
  "xctrace": {
    "template": "Activity Monitor",
    "trace_from": $(json_str "$RUN_TRACE_FROM"),
    "trace_bound_ms": $RUN_TRACE_MS,
    "trace_ended_by_bound": $(json_bool "$RUN_TRACE_ENDED_BY_BOUND"),
    "trace_ended_early": $(json_bool "$IOS_TRACE_ENDED_EARLY"),
    "end_reason": $(json_str "$IOS_TRACE_END_REASON"),
    "trace": "trace.trace"
  },
  "playback": {
    "confirmed_at_position_ms": $(json_str "$RUN_PLAYBACK_POSITION_MS")
  }
}
JSON
}
