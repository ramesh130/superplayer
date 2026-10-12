#!/usr/bin/env bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# third-party/moq/swift/build-xcframework.sh: MoQ's Swift bindings and the XCFramework under them,
# built from the same moq-dev/moq revision third-party/moq/m2 was, with the crate's default
# features off (#351), and written into this directory. Run by hand, on macOS, when the revision
# moves; nothing in CI runs it, and third-party/moq/README.md's *The Swift bindings* is the manual.
#
#   third-party/moq/swift/build-xcframework.sh <moq checkout> [<cargo target dir>]
#
# Needs rustup (the workspace's rust-toolchain.toml pins the compiler, and the targets are added to
# that toolchain by name — the trap README.md's Android recipe describes) and Xcode. The cargo target
# directory takes several gigabytes; point it somewhere with room, and delete it afterwards.
set -euo pipefail

usage() {
    sed -n '9,13p' "$0" | sed 's/^# \{0,1\}//' >&2
}

[ $# -ge 1 ] && [ $# -le 2 ] || {
    usage
    exit 2
}

here="$(cd "$(dirname "$0")" && pwd)"
source_dir="$(cd "$1" && pwd)"
target_dir="${2:-$source_dir/target}"
mkdir -p "$target_dir"
target_dir="$(cd "$target_dir" && pwd)"

# The revision third-party/moq/m2 was built from (README.md), so the two platforms' bindings are
# generated from one source and cannot disagree about which functions exist.
revision=4e419f9
# The workspace's own pin (rust-toolchain.toml), named so a target is added to it and not to the
# default toolchain.
toolchain=1.95.0-aarch64-apple-darwin
# The iOS device and the arm64 iOS simulator, and nothing else: README.md's *The Swift bindings*
# argues leaving out tvOS and the x86_64 simulator, which is a question of committed bytes.
targets=(aarch64-apple-ios aarch64-apple-ios-sim)

fail() {
    printf 'build-xcframework.sh: %s\n' "$*" >&2
    exit 1
}

actual="$(git -C "$source_dir" rev-parse --short=7 HEAD)"
[ "$actual" = "$revision" ] || fail "$source_dir is at $actual, not $revision"
[ -z "$(git -C "$source_dir" status --porcelain)" ] || fail "$source_dir has local changes"

rustup target add --toolchain "$toolchain" "${targets[@]}"

staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT

cd "$source_dir"
export CARGO_TARGET_DIR="$target_dir"

# The host library, which the bindings are generated from: library mode reads the exported
# functions' metadata out of the binary, so the generated Swift and the library cannot disagree.
cargo "+$toolchain" build -p moq-ffi --no-default-features --release
cargo "+$toolchain" run -p moq-ffi --no-default-features --release --bin uniffi-bindgen -- \
    generate --library "$target_dir/release/libmoq_ffi.dylib" --language swift \
    --out-dir "$staging/bindings"

# The slices. Thin LTO and one codegen unit are upstream's own size settings for this staticlib
# (rs/moq-ffi/build.sh: "cutting it by ~60%"), scoped to these builds as upstream scopes them; the
# deployment target is the package's floor, so the objects are built for what links them.
for target in "${targets[@]}"; do
    CARGO_PROFILE_RELEASE_LTO=thin CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1 \
        IPHONEOS_DEPLOYMENT_TARGET=17.0 \
        cargo "+$toolchain" build -p moq-ffi --no-default-features --release --lib \
        --target "$target"
    mkdir -p "$staging/$target"
    # Unstripped, as m2's libraries are, so the evidence below — and README.md's — can be re-checked
    # against the committed library rather than against a claim about it. And not stripped of debug
    # information either, although that would save about a quarter: `strip -S` leaves aws-lc's
    # hand-written assembly objects with relocations into sections that are gone, and Xcode's linker
    # then refuses the archive ("address … not in any section", p384_montjscalarmul.o and others).
    cp "$target_dir/$target/release/libmoq_ffi.a" "$staging/$target/"
    # #351's subtraction, read off what is about to be committed: no codec crate, no encoder or
    # decoder export. The crate names are searched in the archive's raw bytes as well as its symbol
    # table, because Xcode's nm cannot read the LLVM bitcode Rust's own std objects carry (it reports
    # "Unknown attribute kind" and exits non-zero) and would otherwise skip exactly those objects.
    symbols="$(xcrun nm -a "$staging/$target/libmoq_ffi.a" 2>/dev/null || true)"
    for absent in symphonia openh264; do
        count="$(grep -ci "$absent" <<<"$symbols" || true)"
        [ "$count" = 0 ] || fail "$target carries $count $absent symbols"
        count="$(LC_ALL=C grep -aci "$absent" "$staging/$target/libmoq_ffi.a" || true)"
        [ "$count" = 0 ] || fail "$target carries $count lines of bytes naming $absent"
    done
    count="$(grep -ciE "uniffi_moq_ffi_fn_.*(en|de)coder" <<<"$symbols" || true)"
    [ "$count" = 0 ] || fail "$target exports $count encoder or decoder functions"
done

mkdir -p "$staging/headers"
cp "$staging/bindings/moqFFI.h" "$staging/headers/"
cp "$staging/bindings/moqFFI.modulemap" "$staging/headers/module.modulemap"

libraries=()
for target in "${targets[@]}"; do
    libraries+=(-library "$staging/$target/libmoq_ffi.a" -headers "$staging/headers")
done
rm -rf "$here/MoqFFI.xcframework"
xcodebuild -create-xcframework "${libraries[@]}" -output "$here/MoqFFI.xcframework"

mkdir -p "$here/Sources/MoqFFI"
cp "$staging/bindings/moq.swift" "$here/Sources/MoqFFI/moq.swift"

echo "wrote $here/MoqFFI.xcframework and $here/Sources/MoqFFI/moq.swift from $revision"
du -sh "$here/MoqFFI.xcframework"
