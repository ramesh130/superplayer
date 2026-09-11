# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# The one place a Perfetto trace config is built.
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
