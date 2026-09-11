# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# The smallest real run: launch, confirm playback, keep playing for a while, confirm it still is.
#
# It measures nothing. It exists to prove the harness end to end — a device, a fresh build, a trace
# and the metadata — and as the template a consumer's scenario starts from. See ../README.md for what
# a scenario file may define.

SCENARIO_DESCRIPTION="Plays the demo's default stream for SMOKE_SECONDS (default 10) and checks it is still advancing."

# The trace covers the whole run, launch included.
SCENARIO_TRACE_FROM=launch
SCENARIO_TRACE_MS=120000

scenario_drive() {
    SMOKE_START_MS="$RUN_PLAYBACK_POSITION_MS"
    sleep "${SMOKE_SECONDS:-10}"
    SMOKE_END_MS="$(wait_for_playback 30)"
}

scenario_report() {
    printf 'Played for %ss after playback was confirmed; the session position went from %s ms to %s ms.\n' \
        "${SMOKE_SECONDS:-10}" "$SMOKE_START_MS" "$SMOKE_END_MS"
}
