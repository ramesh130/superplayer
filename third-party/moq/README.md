# MoQ's Kotlin bindings, built here

`m2/` is a Maven repository committed to this repository. It holds one coordinate,
`dev.moq:moq-ffi`, and `superplayer-moq` is the only module that resolves it.

This directory exists because the artifact is **built on one machine and is nobody else's to
resolve**. That is not a shortcut — it is the result of issue #351, and the reason
`superplayer-moq` is kept out of the published set (`settings.gradle.kts`'s `unpublishedModules`,
ADR-0017 rule 1). Issue #369 tracks what publishing it would take.

## Why not the artifact on Maven Central

`dev.moq:moq-ffi` is published to Maven Central, built with the crate's **default features on**.
Those defaults pull in `symphonia-codec-aac` and `symphonia-core`, both **MPL-2.0**, which
`CONTRIBUTING.md`'s licence policy does not accept — and `openh264`'s vendored C++ with it.

#351 settled that by building the crate `--no-default-features` instead of amending the policy:
upstream's own manifest documents the opt-out ("skip the ~40 crates that only the codecs pull in"),
and ADR-0018 rule 1 says nothing in this library decodes, so dropping a decoder is pure
subtraction. `cargo tree` counts seven `symphonia` entries in the resolved graph with the defaults
and **zero** without, and the built binary answers the same way:

```
llvm-nm -a libmoq_ffi.so | grep -ci symphonia                                       → 0
llvm-nm -a libmoq_ffi.so | grep -ci openh264                                        → 0
llvm-nm -D --defined-only libmoq_ffi.so | grep -ciE "uniffi_moq_ffi_fn_.*(en|de)coder"  → 0
```

That is why Maven Central must not be able to answer for this group. `settings.gradle.kts`
declares this directory with `exclusiveContent` filtered to `dev.moq`, so Central is not asked at
all; `MoqBindingsResolutionTest` asserts what was resolved rather than trusting the declaration.

**One thing the codec subtraction does not remove**, and it was found by enumerating the graph
crate by crate for this ticket rather than by reasoning about it: UniFFI is Mozilla's and is
MPL-2.0, and `uniffi_core` is the runtime scaffolding rather than a generator — `llvm-nm` finds 342
of its symbols in `libmoq_ffi.so`. So the binary here contains weak, file-scoped copyleft, which
`CONTRIBUTING.md` accepts for what is never distributed in a published artifact and refuses in one.
Keeping `superplayer-moq` out of the published set is what makes that true, and #369 has to answer
it before the module could ship. `CRATES.md` is the enumeration and `THIRD_PARTY.md` the argument.

## What is here, and what it is not

| Coordinate | What it carries |
| --- | --- |
| `dev.moq:moq-ffi` | the Kotlin Multiplatform root module |
| `dev.moq:moq-ffi-android` | `uniffi.moq.*` plus `jni/arm64-v8a/libmoq_ffi.so` |
| `dev.moq:moq-ffi-jvm` | `uniffi.moq.*` plus `darwin-aarch64/libmoq_ffi.dylib` |

All three at `0.3.19-superplayer-local`: the crate's own `0.3.19`, with the suffix saying what
built it. The version is **exact** and no range survives anywhere in the resolution, which
ADR-0018 rule 13 requires — two builds naming one version of a wrapper must not link different
native code.

Three things are deliberately **not** here.

- **`dev.moq:moq`**, upstream's ergonomic wrapper. It does not compile against a
  `--no-default-features` build at all: its `Aliases.kt` and `Flows.kt` re-export `MoqAudioFrame`,
  `MoqVideoCodec` and a dozen more types that live behind the `audio` and `video` features, so the
  wrapper's build fails with unresolved references. The raw bindings are what this repository
  links, and everything Phase 14 consumes — the catalog, media subscription, the frame pump and
  the statistics snapshot — is in the unconditional half of the crate.
- **`libc++_shared.so`.** Upstream's `package.sh` stages the NDK's C++ runtime beside every
  Android `.so`, because `openh264`'s vendored C++ links against it. With the codecs off,
  `llvm-readelf -d libmoq_ffi.so` lists `libdl`, `libm` and `libc` and no C++ runtime, so it is
  removed from the staged `jniLibs` before the artifact is assembled. That takes 9 MB off the AAR
  and removes a real duplicate-merge hazard for an app that ships its own C++.
- **The `-sources` and `-javadoc` jars**, which nothing reads and which are half the bytes.

## Stated limits

- **One ABI.** `arm64-v8a` only. `armeabi-v7a` and `x86_64` are unbuilt and untested, because the
  host this was built on is Apple Silicon and its only Android system image is arm64. An
  Intel-host emulator cannot run this module.
- **Two builds of one crate.** The Android `.so` and the host `.dylib` come from the same checkout
  at the same commit with the same features, but they are separate compilations for separate
  targets. What a JVM test proves about the host build is a strong indication about the Android
  one and not a proof of it.
- **Not reproducible by anyone else.** Nothing in CI builds this, and the recipe below needs a
  Rust toolchain and an NDK that no other check installs.
- **Unstripped, on purpose.** The binaries keep their symbol tables so that the `llvm-nm` evidence
  above can be re-checked against the committed artifact rather than against a claim about it.

`docs/testing.md`'s *The MoQ bindings* states the first two in the idiom that document uses for
every other stand-in.

## Rebuilding

Needs: a Rust toolchain via `rustup`, the Android NDK, and a JDK 17. It builds in about two
minutes once the toolchain is right.

```bash
# 0. The checkout. moq-dev/moq at the commit this artifact was built from.
git clone https://github.com/moq-dev/moq && cd moq && git checkout 4e419f9

# 1. The Rust target — and this is the trap that has already cost one failed build.
#    The workspace pins Rust 1.95.0 in rust-toolchain.toml. `rustup target add
#    aarch64-linux-android` adds the target to the *default* toolchain, so the build fails with
#    "can't find crate for std" while `rustup target list --installed` cheerfully reports the
#    target present. Name the toolchain:
rustup target add --toolchain 1.95.0-aarch64-apple-darwin aarch64-linux-android

# 2. The Android library. `aarch64-linux-android24-clang` is the API-24 clang specifically,
#    matching the wrapper's own minSdk of 24 rather than the NDK's default.
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
export CC_aarch64_linux_android="$TC/aarch64-linux-android24-clang"
export AR_aarch64_linux_android="$TC/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TC/aarch64-linux-android24-clang"
cargo build -p moq-ffi --no-default-features --release --target aarch64-linux-android

# 3. The host library, for the JVM unit tests.
cargo build -p moq-ffi --no-default-features --release

# 4. The Kotlin bindings, generated from the library that was just built — so the bindings and the
#    binary cannot disagree about which functions exist. This is kt/scripts/generate.sh's second
#    half, run by hand because that script rebuilds moq-ffi *with* its defaults first.
cargo run -p moq-ffi --no-default-features --release --bin uniffi-bindgen -- \
    generate --library target/release/libmoq_ffi.dylib --language kotlin --no-format \
    --out-dir /tmp/moq-bindings

# 5. Stage what upstream's packaging script expects: one directory per cargo target.
mkdir -p /tmp/moq-libs/aarch64-linux-android /tmp/moq-libs/aarch64-apple-darwin
cp target/aarch64-linux-android/release/libmoq_ffi.so /tmp/moq-libs/aarch64-linux-android/
cp target/release/libmoq_ffi.dylib /tmp/moq-libs/aarch64-apple-darwin/

# 6. Assemble and publish, with upstream's own script rather than by hand.
export GRADLE_CMD=/path/to/superPlayer/gradlew
kt/scripts/package.sh --version 0.3.19-superplayer-local \
    --lib-dir /tmp/moq-libs --bindings-dir /tmp/moq-bindings --output /tmp/moq-dist

# 7. Drop the NDK C++ runtime the script staged (see above) and re-assemble.
rm kt/moq-ffi/src/androidMain/jniLibs/arm64-v8a/libc++_shared.so
rm -rf /tmp/moq-dist/maven-local
"$GRADLE_CMD" -p kt -Pmoqffi.version=0.3.19-superplayer-local -Pandroid.enabled=true \
    -Dmaven.repo.local=/tmp/moq-dist/maven-local :moq-ffi:assemble :moq-ffi:publishToMavenLocal

# 8. Copy dev/moq/** into this directory's m2/, leaving out the -sources and -javadoc jars.
```

A rebuild at a different version means moving `moqFfi` in `gradle/libs.versions.toml` and nothing
else: that is the only place the version is written down (ADR-0001 rule 3), and the test that
reads it is handed it from there.

## The Swift bindings

`swift/` is the iOS half (#434): a Swift package named `MoqFFI` — UniFFI's generated Swift in
`Sources/MoqFFI/moq.swift` over `MoqFFI.xcframework`, a static library per slice — and
`build-xcframework.sh`, the script that writes both. `ios/MoQ/`'s `SuperPlayerMoQ` is the only
package that resolves it, by path.

**The same source, the same subtraction.** The script refuses a checkout that is not at `4e419f9`,
the commit `m2/` was built from, so the two platforms' bindings are generated from one revision and
cannot disagree about which functions exist. It builds `--no-default-features` for #351's reason, and
then checks the libraries it is about to write rather than trusting the flag: no `symphonia` and no
`openh264` in either slice's symbol table **or its raw bytes** (Xcode's `nm` cannot read the LLVM
bitcode in Rust's own std objects, says "Unknown attribute kind" and skips them, so a symbol-table
count alone would be blind to exactly those objects), and no encoder or decoder export. As built:
`uniffi_core` appears 131 times in the device slice's symbol table, so the MPL-2.0 runtime is linked
here exactly as it is into the `.so` — the same fact, the same admission (`CONTRIBUTING.md`'s #364
amendment and its #434 reading).

**Committed, not built in CI, and why.** The macOS CI job has no Rust toolchain and `ios/check` must
be what a contributor and that job both run (ADR-0021), so the alternatives were committing the
binary or teaching CI to build a Rust workspace from source on every run. The first is what `m2/`
already does for Android, and it keeps `ios/check` a pure Xcode run. The cost is bytes, and they are
stated: **58 MB on disk, about 20 MB in Git's compressed objects** — two slices of about 30 MB each,
against `m2/`'s 15 MB. What was done to keep it there:

- **Two slices, iOS device and the arm64 iOS simulator.** tvOS builds — the targets are in rustup —
  but two more slices would double the bytes for a platform with no MoQ device ticket and no demo
  screen, so `SuperPlayerMoQ` declares iOS alone and the demo links it on iOS only. The x86_64
  simulator is left out for the same reason, and the demo excludes that architecture from its
  simulator builds, which is the one thing it costs: the demo no longer builds for an Intel Mac's
  simulator.
- **Thin LTO and one codegen unit**, upstream's own size settings for this staticlib
  (`rs/moq-ffi/build.sh`), which take a slice from 80 MB to 30 MB.
- **Not stripped.** Partly for `m2/`'s reason — the evidence above has to be re-checkable against the
  committed library — and partly because stripping does not work: `strip -S` leaves aws-lc's
  hand-written assembly objects with relocations into removed sections, and Xcode's linker then
  refuses the archive ("address … not in any section", `p384_montjscalarmul.o` among others). `llvm-objcopy
  --strip-debug` does link, and would save a quarter; it was not taken, because it needs a tool from
  outside Xcode in the recipe for 4 MB a slice.
- **A static library, not a dynamic framework.** A `.dylib` slice is about half the size, but a
  dynamic framework is a second packaging format to get right — install names, embedding, an
  `Info.plist` per slice — for an artifact upstream itself ships static.

**What the Swift package links beyond the library**: `Security`, for rustls' platform verifier
(`SecTrust…`), which a static library cannot declare for itself; upstream's own template also names
the codec frameworks and `libc++`, which #351's build removed. The iOS dependency graph is the
Android one with six crates exchanged for Apple's platform equivalents — `CRATES.md`'s *The iOS
graph*.

### Rebuilding

Needs rustup, Xcode, and several gigabytes of room for cargo's target directory. About twenty
minutes from cold on an M-series laptop, most of it the host build the bindings are generated from.

```bash
git clone https://github.com/moq-dev/moq /tmp/moq && git -C /tmp/moq checkout 4e419f9
third-party/moq/swift/build-xcframework.sh /tmp/moq /tmp/moq-target
rm -rf /tmp/moq-target    # the target directory is several gigabytes
```

The script adds the two Apple targets to the workspace's pinned toolchain by name — the trap step 1
above describes — and leaves everything else as it found it. Run `ios/check` after it.
