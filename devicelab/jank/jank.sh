# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# The jank scenario's device-free half: the gestures it makes, as a plan. Pure functions over text,
# checked by test/selftest. README.md is the manual; ../scenarios/jank.sh carries the plan out.

# Prints the scenario's gestures, one per line, for `$1` rounds of `$2` swipes each. A round scrolls down
# the feed with `$2` swipes and then taps twice, pausing the row being watched and playing it again,
# with a pause before each tap for the scroll to come to rest:
#
#   swipe     a swipe up the screen: the feed scrolls down, and flings
#   rest      a wait for the list to come to rest, and for the frames of a tap to be its own
#   tap       a tap on the middle of the feed
#
# Two taps rather than one, so that every round leaves the feed playing as it found it, and the next
# round's scroll is the same work whichever run it is in.
jank_plan() {
    local rounds="$1" swipes="$2" round=1 swipe
    while [ "$round" -le "$rounds" ]; do
        swipe=1
        while [ "$swipe" -le "$swipes" ]; do
            printf 'swipe\n'
            swipe=$((swipe + 1))
        done
        printf 'rest\ntap\nrest\ntap\nrest\n'
        round=$((round + 1))
    done
}

# How many of a plan's lines (stdin) are `$1`: how many swipes or taps it makes.
jank_plan_count() {
    grep -c -x "$1" || true
}
