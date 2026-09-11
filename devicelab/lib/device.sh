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

# `adb devices` on stdin → the serials whose state is `$1`.
serials_in_state() {
    awk -F '\t' -v state="$1" 'NF >= 2 && $2 == state {print $1}'
}

# `adb devices` on stdin → the serials ready for commands.
ready_serials() { serials_in_state device; }

adb_devices() { with_timeout 30 "$ADB" devices; }

# `adb -s $SERIAL`, bounded. ADB_TIMEOUT overrides the default for a call known to be slow.
adb_s() { with_timeout "${ADB_TIMEOUT:-60}" "$ADB" -s "$SERIAL" "$@"; }

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
wait_for_boot() {
    local serial="$1" timeout="$2" deadline state
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
        [ "$(date +%s)" -lt "$deadline" ] ||
            die "$serial did not finish booting within ${timeout}s (state: ${state:-not listed})"
        sleep 5
    done
}

# Leaves $SERIAL naming a booted device that answers.
ensure_device() {
    local boot_timeout="${BOOT_TIMEOUT:-600}" devices ready offline avd

    if [ -n "${SERIAL:-}" ]; then
        log "using $SERIAL, as asked"
        wait_for_boot "$SERIAL" "$boot_timeout"
        device_answers "$SERIAL" || die "$SERIAL is listed but not answering; it was named explicitly, so it is left alone"
        return
    fi

    devices="$(adb_devices)"
    ready="$(printf '%s\n' "$devices" | ready_serials)"
    offline="$(printf '%s\n' "$devices" | serials_in_state offline)"

    if [ "$(printf '%s' "$ready" | grep -c .)" -gt 1 ]; then
        die "several devices are attached ($(printf '%s' "$ready" | tr '\n' ' ')); choose one with --serial"
    fi

    if [ -n "$ready" ]; then
        SERIAL="$ready"
        if device_answers "$SERIAL"; then
            log "adopting $SERIAL, which is already running"
        else
            avd="$(emulator_avd "$SERIAL")"
            [ "$avd" = "$(device_property avd)" ] ||
                die "$SERIAL is listed but not answering, and is not the harness's AVD, so it is left alone"
            kill_emulator "$SERIAL" "$avd"
            boot_avd
        fi
    elif [ -n "$offline" ]; then
        # Most often an emulator part-way through booting: wait for it rather than start a second.
        SERIAL="$(printf '%s\n' "$offline" | head -1)"
        log "waiting for $SERIAL, which is listed offline"
    else
        boot_avd
    fi

    wait_for_boot "$SERIAL" "$boot_timeout"
    device_answers "$SERIAL" || die "$SERIAL booted but is not answering"
}

# Fails the run if the device has gone since ensure_device. A trace from a device that died part-way
# describes a run that did not happen, so it is not kept as a result.
assert_device_alive() {
    device_answers "$SERIAL" ||
        die "$SERIAL stopped answering during $1; this run's results are discarded (run again to boot a fresh device)"
}
