# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# Stale-artifact detection: proving the demo on the device was built against the library just
# published, rather than against whatever `publishToMavenLocal` last left behind.
#
# This is the footgun that matters most, because nothing fails when it goes off. The demo resolves
# SuperPlayer from Maven local; build it against an old publication and it builds, installs, plays
# and gets measured — as the previous version, with a plausible answer.
#
# So the demo records what it was built against. Its build writes `assets/superplayer-artifacts.txt`
# into the APK: one line per `com.superplayer` artifact on its runtime classpath, as
# "<group>:<module>:<version> <sha256 of the AAR>" (see demo/build.gradle.kts). The harness pulls the
# APK back off the device — the installed one, so an install that silently failed is caught too —
# and compares that against the same hash recomputed from Maven local after its own publish.

# Reads a fingerprint on stdin and prints the same coordinates with the hash of the matching AAR in
# the Maven repository rooted at `$1`, or "missing" where there is none.
published_fingerprint() {
    local m2="$1" coordinate built group module version aar
    while read -r coordinate built; do
        [ -n "$coordinate" ] || continue
        group="${coordinate%%:*}"
        module="${coordinate#*:}"
        version="${module#*:}"
        module="${module%%:*}"
        aar="$m2/${group//.//}/$module/$version/$module-$version.aar"
        if [ -f "$aar" ]; then
            printf '%s %s\n' "$coordinate" "$(sha256_of "$aar")"
        else
            printf '%s missing\n' "$coordinate"
        fi
    done
}

# Fails, naming every difference, unless the fingerprint file `$2` matches Maven local at `$1`.
verify_fingerprint() {
    local m2="$1" built="$2" published coordinate built_hash published_hash stale=0
    if ! grep -q '^com\.superplayer:' "$built"; then
        printf 'the APK records no SuperPlayer artifact at all, so what it was built against is unknown\n' >&2
        return 1
    fi
    published="$(published_fingerprint "$m2" < "$built")"
    while read -r coordinate built_hash; do
        [ -n "$coordinate" ] || continue
        published_hash="$(printf '%s\n' "$published" | awk -v c="$coordinate" '$1 == c {print $2}')"
        if [ "$built_hash" != "$published_hash" ]; then
            stale=1
            printf '%s\n  built against:     %s\n  published locally: %s\n' \
                "$coordinate" "$built_hash" "$published_hash" >&2
        fi
    done < "$built"
    if [ "$stale" -ne 0 ]; then
        printf 'the APK was not built against the SuperPlayer just published; measuring it would measure another version\n' >&2
        return 1
    fi
}
