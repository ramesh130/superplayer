# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# The one place a Perfetto trace config is built, and the one place a trace session is run.
#
# A caller names data sources; this composes `perfetto/base.pbtxt` — buffers, duration, and what every
# trace needs to be read at all — with one `perfetto/sources/<name>.pbtxt` fragment per name. A leak
# hunt asks for `java_hprof,heapprofd`, a jank measurement for `frametimeline`, and both get the same
# base, so the traces they produce differ only in what they asked for.
#
# Fragments are text-format `TraceConfig` and may use two placeholders, written with an at-sign on
# each side: DURATION_MS and PACKAGE. Nothing else is substituted, and a placeholder that survives
# composition fails it rather than reaching the device.
#
# spec: https://perfetto.dev/docs/concepts/config
# ref:  https://android.googlesource.com/platform/external/perfetto/+/main/protos/perfetto/config/trace_config.proto

perfetto_sources() {
    local fragment
    for fragment in "$DEVICELAB_HOME"/perfetto/sources/*.pbtxt; do
        basename "$fragment" .pbtxt
    done
}

# Prints the config for a trace of `$1` ms on package `$2` with the comma-separated sources `$3`.
perfetto_config() {
    local duration="$1" package="$2" requested="$3" name chosen="" config
    case "$duration" in
        '' | *[!0-9]* | 0*) die "trace duration must be a positive number of milliseconds, not '$duration'" ;;
    esac

    for name in $(printf '%s' "$requested" | tr ',' ' '); do
        [ -f "$DEVICELAB_HOME/perfetto/sources/$name.pbtxt" ] ||
            die "no Perfetto data source '$name'; available: $(perfetto_sources | tr '\n' ' ')"
        case " $chosen " in
            *" $name "*) ;;
            *) chosen="$chosen $name" ;;
        esac
    done

    config="$(
        cat "$DEVICELAB_HOME/perfetto/base.pbtxt"
        for name in $chosen; do
            printf '\n# --- %s ---\n' "$name"
            cat "$DEVICELAB_HOME/perfetto/sources/$name.pbtxt"
        done
    )"
    config="$(printf '%s\n' "$config" | sed -e "s/@DURATION_MS@/$duration/g" -e "s/@PACKAGE@/$package/g")"
    if printf '%s\n' "$config" | grep -q '@[A-Z_]*@'; then
        die "unsubstituted placeholder in the Perfetto config: $(printf '%s\n' "$config" | grep -o '@[A-Z_]*@' | head -1)"
    fi
    printf '%s\n' "$config"
}

# --- Sessions on the device ------------------------------------------------------------------------
#
# Every trace runs as a *detached* Perfetto session, named by a key, and is stopped by that key. The
# run's own trace is one; a scenario may open more — a heap dump at a moment it chooses, a native
# profile over one phase — and they are all started, stopped and abandoned here.
#
# Not by signal: on API 36 the shell user may not signal the perfetto process, although `ps` lists it
# as shell's, so `kill -TERM` — the obvious way to end a trace early — is refused, and `kill -0`
# reports a live trace as gone. Detaching is Perfetto's own supported way to end a trace from another
# invocation (`--attach=KEY --stop`), and `--is_detached=KEY` answers whether it is still running.
#
# What detaching gives up is `--background-wait`: the two are mutually exclusive, so the start no
# longer waits for every data source to confirm it has started. A pause of TRACE_SETTLE_S (default 2)
# stands in for that confirmation; a source slower to start than that misses the first moments of
# what it was meant to record, and a consumer whose source is known to be slow should raise it.
#
# ref: https://perfetto.dev/docs/reference/perfetto-cli

PERFETTO_DEVICE_DIR=/data/misc/perfetto-traces
PERFETTO_OPEN_SESSIONS=""
PERFETTO_ENDED_BY_BOUND=0

perfetto_device_file() { printf '%s/%s.perfetto-trace\n' "$PERFETTO_DEVICE_DIR" "$1"; }

# Starts session `$1` from config file `$2`, detached.
perfetto_session_start() {
    local key="$1" output
    output="$(adb_s shell perfetto --txt -c - -o "$(perfetto_device_file "$key")" "--detach=$key" \
        < "$2" 2>&1 | tr -d '\r')" || true
    [ "$(perfetto_session_state "$key")" = running ] || die "perfetto did not start session $key: $output"
    PERFETTO_OPEN_SESSIONS="$PERFETTO_OPEN_SESSIONS $key"
    sleep "${TRACE_SETTLE_S:-2}"
}

# `running` while session `$1` exists, `ended` once it does not; anything else fails the run.
perfetto_session_state() {
    local code
    code="$(adb_s shell "perfetto --is_detached=$1 >/dev/null 2>&1; echo \$?" | tr -d '\r')"
    case "$code" in
        0) printf 'running\n' ;;
        2) printf 'ended\n' ;;
        *) die "perfetto could not say whether session $1 is running (exit $code)" ;;
    esac
}

# Waits up to `$2` seconds for session `$1` to end on its own, at its duration.
perfetto_session_wait() {
    local deadline=$(($(date +%s) + $2))
    while [ "$(perfetto_session_state "$1")" = running ]; do
        [ "$(date +%s)" -lt "$deadline" ] || die "perfetto session $1 was still running after $2 s"
        sleep 2
    done
}

# Ends session `$1` if it is still running — which returns once perfetto has written the trace — and
# pulls the trace to `$2`. Sets PERFETTO_ENDED_BY_BOUND to 1 when the session had already ended.
perfetto_session_stop() {
    local key="$1" device_file
    device_file="$(perfetto_device_file "$key")"
    PERFETTO_ENDED_BY_BOUND=0
    if [ "$(perfetto_session_state "$key")" = running ]; then
        ADB_TIMEOUT=120 adb_s shell perfetto "--attach=$key" --stop >/dev/null 2>&1 ||
            die "perfetto did not stop session $key"
    else
        PERFETTO_ENDED_BY_BOUND=1
    fi
    PERFETTO_OPEN_SESSIONS="$(printf '%s\n' $PERFETTO_OPEN_SESSIONS | grep -vx "$key" | tr '\n' ' ' || true)"
    adb_pull "$device_file" "$2" || die "could not pull $device_file"
    adb_s shell rm -f "$device_file"
    [ -s "$2" ] || die "perfetto wrote an empty trace for session $key"
}

# Stops every session still open and deletes its file. For a run that is failing.
perfetto_abandon_all() {
    local key
    for key in $PERFETTO_OPEN_SESSIONS; do
        adb_s shell perfetto "--attach=$key" --stop >/dev/null 2>&1 || true
        adb_s shell rm -f "$(perfetto_device_file "$key")" >/dev/null 2>&1 || true
    done
    PERFETTO_OPEN_SESSIONS=""
}

# --- A scenario's own traces -----------------------------------------------------------------------
#
# For a scenario that needs a trace other than the run's: named, with its own data sources, and
# written into the run directory as `<name>.perfetto-trace` beside `<name>.perfetto-config.pbtxt`.
# The base config comes with every one of them, so each records which build of the demo it saw.

# Starts trace `$1` with data sources `$2`, bounded at `$3` ms. `trace_end` stops it.
trace_begin() {
    perfetto_config "$3" "$DEMO_PACKAGE" "$2" > "$RUN_DIR/$1.perfetto-config.pbtxt"
    perfetto_session_start "devicelab-$RUN_ID-$1" "$RUN_DIR/$1.perfetto-config.pbtxt"
}

trace_end() {
    perfetto_session_stop "devicelab-$RUN_ID-$1" "$RUN_DIR/$1.perfetto-trace"
}

# Trace `$1` with data sources `$2`, run for `$3` ms and pulled. For a data source that records a
# moment rather than a span — java_hprof's heap dump, taken when its data source starts — this is how
# a scenario captures that moment where it chooses to, rather than where the run's trace began.
capture_trace() {
    trace_begin "$1" "$2" "$3"
    perfetto_session_wait "devicelab-$RUN_ID-$1" $(($3 / 1000 + 60))
    trace_end "$1"
}
