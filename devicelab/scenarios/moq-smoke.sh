# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# i9's device exit (#438, `docs/ios-plan.md` §7 and §3.10): a MoQ broadcast over QUIC from an iPhone,
# as the XCUITest `MoqLiveSessionSmokeTests` (ios/Demo/SuperPlayerDemoUITests) — the transport opened
# in the test runner with no player, as Android's instrumented `MoqLiveSessionSmokeTest` opens it, and
# then the demo's `MoqScreen` playing the same broadcast, read off its overlay.
#
# On Android the same smoke is `devicelab/moq-smoke`, a sibling of `lab` rather than a scenario,
# because it drives no demo and records no trace. On iOS it is a scenario because the lab's iOS half
# already has the device, the signing and the build it needs, and because the one test target holds
# both halves — the transport and the demo — where Android needs two. It records no trace either:
# xcodebuild owns the app for the test's length (`SCENARIO_IOS_UI_TEST`, `ios_test_demo`).
#
#   SP_MOQ_BROADCAST=moq://host/namespace/name   the broadcast (default: the demo's own)
#   SP_MOQ_PROBE_MAPPINGS=true                   ask the relay about both address splits even when
#                                                the subscription works (#367's two defects)
#
# Not part of any check: it needs a device and the internet, which docs/testing.md and ADR-0021 bar.
# docs/testing.md's *The first real MoQ session on an iPhone* is the record.

SCENARIO_DESCRIPTION="iOS: a MoQ broadcast over QUIC — the transport in an XCUITest runner, then the demo playing it (SP_MOQ_BROADCAST)."
SCENARIO_PLATFORMS=ios
SCENARIO_IOS_UI_TEST=SuperPlayerDemoUITests/MoqLiveSessionSmokeTests
SCENARIO_IOS_TEST_ENV=()
[ -z "${SP_MOQ_BROADCAST:-}" ] || SCENARIO_IOS_TEST_ENV+=("SP_MOQ_BROADCAST=$SP_MOQ_BROADCAST")
[ -z "${SP_MOQ_PROBE_MAPPINGS:-}" ] || SCENARIO_IOS_TEST_ENV+=("SP_MOQ_PROBE_MAPPINGS=$SP_MOQ_PROBE_MAPPINGS")

# The test has run by the time this is called; what is left is reading where the demo got to, which
# run.json and the report's header carry as the run's confirmed playback.
scenario_drive() {
    RUN_PLAYBACK_POSITION_MS="$(ios_observation demo demo.position <"$RUN_DIR/observations.txt")"
}

scenario_report() {
    printf 'xcodebuild exited %s. Outcomes:\n\n' "$RUN_TEST_EXIT"
    printf '| Test | Outcome |\n| --- | --- |\n'
    awk -F '\t' '{ printf "| `%s` | %s |\n", $1, $2 }' "$RUN_DIR/outcomes.tsv"
    printf '\nWhat the tests observed, before any assertion ran:\n\n```\n'
    if [ -s "$RUN_DIR/observations.txt" ]; then
        cat "$RUN_DIR/observations.txt"
    else
        printf 'No observation block reached the log: the run did not get as far as observing anything —\n'
        printf 'test.log says whether the runner failed to install, to start, or to enable UI automation.\n'
    fi
    printf '```\n'
}

# A failed test, a runner that never ran one, or no outcome at all is a failure; a skip — the relay
# answered and announced nothing — is not, for Android's reason: a run that goes red whenever a public
# relay is quiet is a run whose red stops being read. The report says it skipped.
scenario_verdict() {
    [ -s "$RUN_DIR/outcomes.tsv" ] || return 1
    ! cut -f2 "$RUN_DIR/outcomes.tsv" | grep -qv -E '^(passed|skipped)$'
}
