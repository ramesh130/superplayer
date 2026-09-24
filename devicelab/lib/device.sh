# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# Getting to a device that is booted and answering, whether one was attached or not.
#
# Four behaviours here are each a failure somebody already hit by hand (CLAUDE.md, "Running the demo
# on an emulator"):
#
# - An emulator is listed `offline` for a while after it appears. Readiness is `sys.boot_completed`,
#   never the device listing, and an offline emulator is waited on rather than replaced.
# - An emulator can stay listed as `device` with an adbd that no longer answers. Every call is bounded
#   (`with_timeout`), and a device is probed before it is trusted.
# - An emulator exits on its own — a crash, a host sleep. One that has gone is booted again; one
#   running the harness's own AVD that has stopped answering is killed and booted again, because that
#   AVD is disposable.
# - A device someone named with --serial is theirs. It is waited on and probed, and never restarted.
#
# A fifth is the kind of device. device.properties describes a phone and a television, and a run on
# the other kind would measure a screen it did not ask for, so a device is adopted only when it is the
# kind asked for (`--device`, or DEVICELAB_DEVICE).

# `adb devices` on stdin → the serials whose state is `$1`.
serials_in_state() {
    awk -F '\t' -v state="$1" 'NF >= 2 && $2 == state {print $1}'
}

# `adb devices` on stdin → the serials ready for commands.
ready_serials() { serials_in_state device; }

adb_devices() { with_timeout 30 "$ADB" devices; }

# `adb -s $SERIAL`, bounded. ADB_TIMEOUT overrides the default for a call known to be slow.
adb_s() { with_timeout "${ADB_TIMEOUT:-60}" "$ADB" -s "$SERIAL" "$@"; }

# Whether the run's device gives the shell root through `su`: an emulator's userdebug image does, a
# production device does not. What a scenario does with it is its own — the leak hunt forces a GC, the
# startup scenario drops the page cache — and each says what it does without.
device_has_root() {
    [ "$(adb_s shell su 0 id -u 2>/dev/null | tr -d '\r')" = 0 ]
}

device_answers() {
    [ "$(with_timeout 15 "$ADB" -s "$1" shell echo ok 2>/dev/null | tr -d '\r')" = ok ]
}

# The AVD a running emulator was booted from, or nothing. Read from the device when it answers, and
# otherwise from the emulator process's own command line.
emulator_avd() {
    local avd
    avd="$(with_timeout 15 "$ADB" -s "$1" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')"
    if [ -z "$avd" ] && [ -n "$(emulator_pids "$(device_property avd)")" ]; then
        # Unanswering, so ask the host. Only meaningful when exactly one emulator is running.
        [ "$(serials_in_state device < <(adb_devices) | grep -c '^emulator-')" -le 1 ] &&
            avd="$(device_property avd)"
    fi
    printf '%s\n' "$avd"
}

emulator_pids() {
    ps -ax -o pid=,command= | awk -v avd="$1" '
        /qemu-system/ {
            for (i = 2; i < NF; i++) if ($i == "-avd" && $(i + 1) == avd) print $1
        }'
}

kill_emulator() {
    local serial="$1" avd="$2" pid
    log "restarting $serial: it is running the harness's AVD ($avd) and has stopped answering"
    with_timeout 15 "$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true
    sleep 3
    for pid in $(emulator_pids "$avd"); do
        kill "$pid" 2>/dev/null || true
    done
    sleep 3
    for pid in $(emulator_pids "$avd"); do
        kill -9 "$pid" 2>/dev/null || true
    done
}

host_abi() {
    case "$(uname -m)" in
        arm64 | aarch64) printf 'arm64-v8a\n' ;;
        *) printf 'x86_64\n' ;;
    esac
}

# Boots the harness's AVD on a free console port and sets SERIAL to it. Headless when $HEADLESS is 1.
#
# Sets globals rather than printing: run inside $(...) the emulator PID would be lost with the
# subshell, and wait_for_boot could not notice an emulator that died while booting.
boot_avd() {
    local avd port emulator log_file
    avd="$(device_property avd)"
    emulator="$(sdk_root)/emulator/emulator"
    [ -x "$emulator" ] || die "no emulator at $emulator"
    "$emulator" -list-avds 2>/dev/null | grep -qx "$avd" ||
        die "no AVD named $avd; create it (see devicelab/README.md) or attach a device"

    port=5554
    while adb_devices | grep -q "^emulator-$port"; do
        port=$((port + 2))
        [ "$port" -le 5584 ] || die "no free emulator console port between 5554 and 5584"
    done

    log_file="${DEVICELAB_WORK:-${TMPDIR:-/tmp}}/emulator-$port.log"
    mkdir -p "$(dirname "$log_file")"
    set -- -avd "$avd" -port "$port" -no-snapshot-load -no-boot-anim
    [ "${HEADLESS:-0}" != 1 ] || set -- "$@" -no-window -no-audio -gpu swiftshader_indirect
    log "booting $avd as emulator-$port (log: $log_file)"
    nohup "$emulator" "$@" > "$log_file" 2>&1 &
    EMULATOR_PID=$!
    EMULATOR_LOG="$log_file"
    SERIAL="emulator-$port"
}

# Waits up to `$2` seconds for `$1` to be listed, answering, and booted.
#
# `$3` is 1 when the harness adopted `$1` rather than being handed it, and may therefore put its own
# AVD in the place of an emulator that leaves the device listing. An emulator listed `offline` is
# usually on its way in, but can be on its way out — crashed, slept, taken down by its `adb` client —
# and then vanishes; polling a name nothing answers to would only wait out the timeout. Once gone it
# cannot be asked which AVD it ran, but nothing of anyone's is left to harm either: an empty listing
# is exactly when ensure_device boots the harness's AVD anyway. A lingering qemu process of that AVD
# would hold its lock, so it is killed first. A device named explicitly, or a physical device, fails at
# once instead, since nothing can be booted in its place. The replacement then gets the full timeout
# a boot is allowed, and SERIAL follows it.
#
# A serial counts as gone only after it has been listed and then missing for VANISHED_GRACE seconds:
# an `adb` server restart empties the listing for a few seconds, and that is not a dead emulator. A
# serial never yet listed is simply waited for, as a device being plugged in is.
wait_for_boot() {
    local serial="$1" timeout="$2" replaceable="${3:-0}" deadline state seen=0 missing_since=""
    local grace="${VANISHED_GRACE:-20}" avd
    deadline=$(($(date +%s) + timeout))
    while :; do
        state="$(adb_devices | awk -F '\t' -v s="$serial" '$1 == s {print $2}')"
        if [ "$state" = device ] &&
            [ "$(with_timeout 15 "$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
            return 0
        fi
        if [ -n "${EMULATOR_PID:-}" ] && ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
            tail -20 "$EMULATOR_LOG" >&2 || true
            die "the emulator exited before $serial finished booting"
        fi
        if [ -n "$state" ]; then
            seen=1 missing_since=""
        elif [ "$seen" = 1 ]; then
            missing_since="${missing_since:-$(date +%s)}"
            if [ $(($(date +%s) - missing_since)) -ge "$grace" ]; then
                case "$replaceable:$serial" in
                    1:emulator-*) ;;
                    *) die "$serial left the device listing while it was being waited on, and nothing can be booted in its place" ;;
                esac
                avd="$(device_property avd)"
                log "$serial left the device listing while it was being waited on; booting $avd in its place"
                [ -z "$(emulator_pids "$avd")" ] || kill_emulator "$serial" "$avd"
                boot_avd
                serial="$SERIAL" replaceable=0 seen=0 missing_since=""
                deadline=$(($(date +%s) + timeout))
                continue
            fi
        fi
        [ "$(date +%s)" -lt "$deadline" ] ||
            die "$serial did not finish booting within ${timeout}s (state: ${state:-not listed})"
        # A boot takes a minute or more, so five seconds between polls; the self-test shortens it.
        sleep "${BOOT_POLL_INTERVAL:-5}"
    done
}

# `pm list features` on stdin → `tv` when the device declares `android.software.leanback`, which Android
# TV devices declare and phones do not; `phone` otherwise; and nothing when there was no answer at all,
# so that a device that did not reply is not taken for a phone.
#
# ref: https://developer.android.com/training/tv/get-started/create
device_kind_of_features() {
    awk '
        /^feature:/ { answered = 1 }
        $0 == "feature:android.software.leanback" { tv = 1 }
        END { if (tv) print "tv"; else if (answered) print "phone" }
    '
}

# The kind of device `$1` is, asked until it answers or KIND_TIMEOUT seconds (default 60) pass, and
# nothing if it never does. Just after `sys.boot_completed` the package manager may not answer yet, and
# a device that has not answered is of no kind rather than of the other one.
device_kind() {
    local deadline kind
    deadline=$(($(date +%s) + ${KIND_TIMEOUT:-60}))
    while :; do
        kind="$(with_timeout 15 "$ADB" -s "$1" shell pm list features 2>/dev/null | tr -d '\r' | device_kind_of_features)"
        [ -z "$kind" ] && [ "$(date +%s)" -lt "$deadline" ] || break
        sleep "${BOOT_POLL_INTERVAL:-5}"
    done
    printf '%s\n' "$kind"
}

# Leaves $SERIAL naming a booted, answering device of the kind `$DEVICELAB_DEVICE` asks for.
#
# Only a device of that kind is adopted. One of the other kind is left running and ignored, so a phone
# emulator and a television emulator can be up side by side and each run takes its own; with none of
# the kind attached, that kind's AVD is booted beside whatever is. A device named with --serial is
# checked rather than chosen: a TV run on a phone would measure a screen the run did not ask for.
ensure_device() {
    local boot_timeout="${BOOT_TIMEOUT:-600}" wanted="${DEVICELAB_DEVICE:-phone}" devices ready offline
    local serial matching stale pass kind

    if [ -n "${SERIAL:-}" ]; then
        log "using $SERIAL, as asked"
        wait_for_boot "$SERIAL" "$boot_timeout"
        device_answers "$SERIAL" || die "$SERIAL is listed but not answering; it was named explicitly, so it is left alone"
        kind="$(device_kind "$SERIAL")"
        [ -n "$kind" ] || die "$SERIAL did not report its features, so whether it is a $wanted device is unknown"
        [ "$kind" = "$wanted" ] ||
            die "$SERIAL is a $kind device, not a $wanted one; name one that is, or choose the kind with --device"
        return
    fi

    # Twice at most. An offline emulator is waited on before its kind can be asked, whichever kind it
    # turns out to be, and one that boots as the other kind sends the choice round once more, where,
    # being ready, it is simply passed over. A device that answers but never reports its features is
    # passed over too, since adopting it would be a guess.
    for pass in 1 2; do
        devices="$(adb_devices)"
        ready="$(printf '%s\n' "$devices" | ready_serials)"
        offline="$(printf '%s\n' "$devices" | serials_in_state offline)"
        matching="" stale=""
        for serial in $ready; do
            if device_answers "$serial"; then
                [ "$(device_kind "$serial")" != "$wanted" ] || matching="$matching $serial"
            elif [ "$(emulator_avd "$serial")" = "$(device_property avd)" ]; then
                stale="$serial"
            fi
        done
        matching="${matching# }"

        case "$matching" in
            *" "*) die "several $wanted devices are attached ($matching); choose one with --serial" ;;
        esac

        if [ -n "$matching" ]; then
            SERIAL="$matching"
            log "adopting $SERIAL, a $wanted device that is already running"
        elif [ -n "$stale" ]; then
            SERIAL="$stale"
            kill_emulator "$SERIAL" "$(device_property avd)"
            boot_avd
        elif [ -n "$offline" ] && [ "$pass" = 1 ]; then
            # Most often an emulator part-way through booting: wait for it rather than start a second.
            SERIAL="$(printf '%s\n' "$offline" | head -1)"
            log "waiting for $SERIAL, which is listed offline"
        else
            boot_avd
        fi

        wait_for_boot "$SERIAL" "$boot_timeout" 1
        device_answers "$SERIAL" || die "$SERIAL booted but is not answering"
        kind="$(device_kind "$SERIAL")"
        [ -n "$kind" ] || die "$SERIAL booted but did not report its features"
        [ "$kind" != "$wanted" ] || return 0
        log "$SERIAL booted as a $kind device; looking for a $wanted device again"
        SERIAL=""
    done
    die "no $wanted device was attached or could be booted"
}

# Fails the run if the device has gone since ensure_device. A trace from a device that died part-way
# describes a run that did not happen, so it is not kept as a result.
assert_device_alive() {
    device_answers "$SERIAL" ||
        die "$SERIAL stopped answering during $1; this run's results are discarded (run again to boot a fresh device)"
}

# `adb pull`, bounded, printing nothing unless it fails. It reports progress on stderr, which would
# otherwise land in the run's output as though it were news.
adb_pull() {
    local output
    output="$(ADB_TIMEOUT=600 adb_s pull "$1" "$2" 2>&1)" || {
        printf '%s\n' "$output" >&2
        return 1
    }
}
