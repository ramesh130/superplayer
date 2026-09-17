# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# What a run records about itself, in one shape for every consumer.
#
# A leak report and a jank report can only be read against each other if they say the same things
# about what they measured, in the same words. So every run writes `run.json` with the same fields,
# and a consumer that wants more adds a file beside it rather than a field inside it.
#
# `schema` is bumped when a field changes meaning, not when one is added.

RUN_METADATA_SCHEMA=1

# Prints a JSON array of strings from the whitespace-separated words in `$1`.
json_str_array() {
    local word first=1
    printf '['
    for word in $1; do
        [ "$first" = 1 ] || printf ', '
        first=0
        json_str "$word"
    done
    printf ']'
}

# Prints the artifacts fingerprint file `$1` as a JSON array of {coordinate, sha256}.
json_artifacts() {
    local coordinate hash first=1
    printf '['
    while read -r coordinate hash; do
        [ -n "$coordinate" ] || continue
        [ "$first" = 1 ] || printf ', '
        first=0
        printf '{"coordinate": %s, "sha256": %s}' "$(json_str "$coordinate")" "$(json_str "$hash")"
    done < "$1"
    printf ']'
}

# JSON's `true`/`false` from a shell `1`/anything else.
json_bool() { if [ "$1" = 1 ]; then printf true; else printf false; fi; }

# Prints run.json from the RUN_*, SP_*, DEMO_* and DEVICE_* variables the run has set.
run_json() {
    cat <<JSON
{
  "schema": $RUN_METADATA_SCHEMA,
  "run_id": $(json_str "$RUN_ID"),
  "started_at": $(json_str "$RUN_STARTED_AT"),
  "finished_at": $(json_str "$RUN_FINISHED_AT"),
  "scenario": $(json_str "$RUN_SCENARIO"),
  "superplayer": {
    "commit": $(json_str "$SP_COMMIT"),
    "tree_dirty": $(json_bool "$SP_TREE_DIRTY"),
    "version": $(json_str "$SP_VERSION"),
    "artifacts": $(json_artifacts "$SP_ARTIFACTS_FILE")
  },
  "media3": {
    "version": $(json_str "$MEDIA3_VERSION")
  },
  "demo": {
    "package": $(json_str "$DEMO_PACKAGE"),
    "build_type": $(json_str "$DEMO_BUILD_TYPE"),
    "debuggable": $(json_bool "$DEMO_DEBUGGABLE"),
    "profileable_by_shell": $(json_bool "$DEMO_PROFILEABLE"),
    "apk_sha256": $(json_str "$DEMO_APK_SHA256")
  },
  "device": {
    "serial": $(json_str "$SERIAL"),
    "kind": $(json_str "${DEVICELAB_DEVICE:-phone}"),
    "emulator": $(json_bool "$DEVICE_IS_EMULATOR"),
    "avd": $(json_str "$DEVICE_AVD"),
    "manufacturer": $(json_str "$DEVICE_MANUFACTURER"),
    "model": $(json_str "$DEVICE_MODEL"),
    "sdk": $(json_str "$DEVICE_SDK"),
    "abi": $(json_str "$DEVICE_ABI"),
    "build_fingerprint": $(json_str "$DEVICE_FINGERPRINT")
  },
  "host": {
    "os": $(json_str "$HOST_OS"),
    "ci": $(json_bool "$HOST_CI")
  },
  "perfetto": {
    "data_sources": $(json_str_array "$RUN_DATA_SOURCES"),
    "trace_from": $(json_str "$RUN_TRACE_FROM"),
    "trace_bound_ms": $RUN_TRACE_MS,
    "trace_ended_by_bound": $(json_bool "$RUN_TRACE_ENDED_BY_BOUND"),
    "config": "perfetto-config.pbtxt",
    "trace": "trace.perfetto-trace"
  },
  "playback": {
    "confirmed_at_position_ms": $(json_str "$RUN_PLAYBACK_POSITION_MS")
  }
}
JSON
}
