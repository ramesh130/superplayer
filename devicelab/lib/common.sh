# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# What every other file here leans on: logging, a timeout that works on a stock macOS, JSON strings,
# the version catalog, hashing, and where the SDK is.
#
# Written for bash 3.2, which is what macOS ships as /bin/bash: no associative arrays, no `mapfile`,
# no `${var,,}`. The harness runs on a contributor's laptop as well as on a CI runner, and the laptop
# is the one a human is debugging a bad result on.

DEVICELAB_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(dirname "$DEVICELAB_HOME")"

log() { printf '[devicelab] %s\n' "$*" >&2; }

die() {
    printf '[devicelab] error: %s\n' "$*" >&2
    exit 1
}

# Runs a command, killing it after `$1` seconds; exits 142 when it had to.
#
# Every `adb` call in the harness goes through this. An emulator can stay listed as `device` while
# its adbd has stopped answering, and an unbounded `adb shell` against it waits forever — which
# reads as a slow run rather than as a dead device. `timeout(1)` is GNU coreutils and absent from a
# stock macOS; perl is present on both, and an `alarm` survives the `exec`.
with_timeout() {
    local seconds="$1"
    shift
    perl -e 'alarm shift @ARGV; exec @ARGV or die "exec $ARGV[0]: $!\n"' "$seconds" "$@"
}

# Prints `$1` as a JSON string literal, quotes included.
json_str() {
    if [ -z "$1" ]; then
        printf '""'
        return
    fi
    printf '%s' "$1" | perl -0777 -pe '
        s/\\/\\\\/g; s/"/\\"/g; s/\n/\\n/g; s/\r/\\r/g; s/\t/\\t/g;
        s/([\x00-\x1f])/sprintf("\\u%04x", ord($1))/ge;
        $_ = "\"$_\"";'
}

# Reads `[versions]` key `$1` from the catalog at `$2` (default: the repository's own).
#
# The same anchored pattern `.github/actions/setup-android-sdk`'s platform install uses, so a
# commented-out line never wins and `media3` is never read out of `media3Foo`.
catalog_version() {
    local key="$1" catalog="${2:-$REPO_ROOT/gradle/libs.versions.toml}" value
    value="$(sed -n "s/^$key = \"\(.*\)\"\$/\1/p" "$catalog" | head -1)"
    [ -n "$value" ] || die "no version '$key' in $catalog"
    printf '%s\n' "$value"
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# The Android SDK, found the way Gradle finds it: the environment first, then `local.properties`.
sdk_root() {
    local dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$dir" ] && [ -f "$REPO_ROOT/local.properties" ]; then
        dir="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | head -1)"
    fi
    [ -n "$dir" ] && [ -d "$dir" ] ||
        die "no Android SDK: set ANDROID_HOME, or write sdk.dir into local.properties"
    printf '%s\n' "$dir"
}

adb_bin() {
    local sdk
    sdk="$(sdk_root)"
    if [ -x "$sdk/platform-tools/adb" ]; then
        printf '%s\n' "$sdk/platform-tools/adb"
    else
        command -v adb || die "adb is neither in \$ANDROID_HOME/platform-tools nor on PATH"
    fi
}

# Reads key `$1` from devicelab/device.properties, the one place the AVDs are described, for the kind
# of device `$DEVICELAB_DEVICE` names: `phone` (the default) reads the bare key and `tv` reads `tv.<key>`.
# The phone's keys stay bare because CI's android-emulator action and every run before the television
# was described read them that way.
device_property() {
    local key value
    case "${DEVICELAB_DEVICE:-phone}" in
        phone) key="$1" ;;
        tv) key="tv.$1" ;;
        *) die "a device is a phone or tv, not '${DEVICELAB_DEVICE}'" ;;
    esac
    value="$(sed -n "s/^$key=//p" "$DEVICELAB_HOME/device.properties" | head -1)"
    [ -n "$value" ] || die "no '$key' in devicelab/device.properties"
    printf '%s\n' "$value"
}

# The SDK package of the system image the described AVD boots, for ABI `$1`.
avd_system_image() {
    printf 'system-images;%s;%s;%s\n' \
        "$(device_property system_image_api)" "$(device_property system_image_tag)" "$1"
}
