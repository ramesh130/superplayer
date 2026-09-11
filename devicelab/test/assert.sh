# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# The assertions every devicelab self-test uses: the harness's own, and each consumer's. Sourced, not
# run; a self-test calls `assert_summary` last, and that is its exit status.

failures=0
passes=0

pass() { passes=$((passes + 1)); }
fail() {
    failures=$((failures + 1))
    printf 'FAIL: %s\n' "$1" >&2
    [ $# -lt 2 ] || printf '%s\n' "$2" | sed 's/^/    /' >&2
}

assert_eq() { # name expected actual
    if [ "$2" = "$3" ]; then pass; else fail "$1" "expected: [$2]
actual:   [$3]"; fi
}

assert_contains() { # name haystack needle
    case "$2" in *"$3"*) pass ;; *) fail "$1" "missing: [$3]
in: [$2]" ;; esac
}

assert_not_contains() { # name haystack needle
    case "$2" in *"$3"*) fail "$1" "unexpected: [$3]" ;; *) pass ;; esac
}

# Prints the tally for self-test `$1` and fails if anything did.
assert_summary() {
    if [ "$failures" -gt 0 ]; then
        printf '%s: %d passed, %d FAILED\n' "$1" "$passes" "$failures" >&2
        return 1
    fi
    printf '%s: %d passed\n' "$1" "$passes"
}
