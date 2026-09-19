# The crates linked into `libmoq_ffi`

Every crate in `moq-ffi`'s dependency graph for `aarch64-linux-android`, built
`--no-default-features` — the enumeration ADR-0018 rule 13 asks for by name rather than by
assertion. `THIRD_PARTY.md`'s *The MoQ bindings* section carries the reading of it; this file is
the list itself, because a summary nobody can check is not evidence.

Read off each crate's own `license` field with `cargo metadata --filter-platform
aarch64-linux-android`, over the crate set `cargo tree -e normal --no-default-features` reports, so
what is here is what cargo resolves rather than what a manifest advertises. Regenerate it the same
way after any rebuild (`README.md`).

**320 crates. No strong copyleft, and one weak-copyleft family** — UniFFI's eight MPL-2.0 crates,
which `THIRD_PARTY.md` argues at length: `uniffi_core` is *linked*, so the binary this repository
builds contains MPL-2.0 code, which the policy accepts only because `superplayer-moq` is not a
published artifact. Everything else is MIT, Apache-2.0, BSD, ISC, Zlib, 0BSD, Unicode-3.0,
CC0-1.0 or CDLA-Permissive-2.0, all permissive and all without a field-of-use restriction.

| Crate | License |
| --- | --- |
| `adler2` | 0BSD OR MIT OR Apache-2.0 |
| `aead` | MIT OR Apache-2.0 |
| `aes` | MIT OR Apache-2.0 |
| `aes-gcm` | Apache-2.0 OR MIT |
| `aho-corasick` | Unlicense OR MIT |
| `anstream` | MIT OR Apache-2.0 |
| `anstyle` | MIT OR Apache-2.0 |
| `anstyle-parse` | MIT OR Apache-2.0 |
| `anstyle-query` | MIT OR Apache-2.0 |
| `anyhow` | MIT OR Apache-2.0 |
| `arrayvec` | MIT OR Apache-2.0 |
| `askama` | MIT OR Apache-2.0 |
| `askama_derive` | MIT OR Apache-2.0 |
| `askama_macros` | MIT OR Apache-2.0 |
| `askama_parser` | MIT OR Apache-2.0 |
| `asn1-rs` | MIT OR Apache-2.0 |
| `asn1-rs-derive` | MIT OR Apache-2.0 |
| `asn1-rs-impl` | MIT/Apache-2.0 |
| `atomic-waker` | Apache-2.0 OR MIT |
| `aws-lc-rs` | ISC AND (Apache-2.0 OR ISC) |
| `aws-lc-sys` | ISC AND (Apache-2.0 OR ISC) AND Apache-2.0 AND MIT AND BSD-3-Clause AND (Apache-2.0 OR ISC OR MIT) AND (Apache-2.0 OR ISC OR MIT-0) |
| `base64` | MIT OR Apache-2.0 |
| `base64` | MIT OR Apache-2.0 |
| `basic-toml` | MIT OR Apache-2.0 |
| `bitflags` | MIT OR Apache-2.0 |
| `block-buffer` | MIT OR Apache-2.0 |
| `byteorder` | Unlicense OR MIT |
| `bytes` | MIT |
| `bytestring` | MIT OR Apache-2.0 |
| `camino` | MIT OR Apache-2.0 |
| `cargo-platform` | MIT OR Apache-2.0 |
| `cargo_metadata` | MIT |
| `cfg-if` | MIT OR Apache-2.0 |
| `chacha20` | MIT OR Apache-2.0 |
| `cipher` | MIT OR Apache-2.0 |
| `clap` | MIT OR Apache-2.0 |
| `clap_builder` | MIT OR Apache-2.0 |
| `clap_derive` | MIT OR Apache-2.0 |
| `clap_lex` | MIT OR Apache-2.0 |
| `colorchoice` | MIT OR Apache-2.0 |
| `combine` | MIT |
| `convert_case` | MIT |
| `cpufeatures` | MIT OR Apache-2.0 |
| `crc32fast` | MIT OR Apache-2.0 |
| `crypto-common` | MIT OR Apache-2.0 |
| `ctr` | MIT OR Apache-2.0 |
| `darling` | MIT |
| `darling_core` | MIT |
| `darling_macro` | MIT |
| `data-encoding` | MIT |
| `der-parser` | MIT OR Apache-2.0 |
| `deranged` | MIT OR Apache-2.0 |
| `derive_more` | MIT |
| `derive_more-impl` | MIT |
| `digest` | MIT OR Apache-2.0 |
| `displaydoc` | MIT OR Apache-2.0 |
| `dns-lookup` | MIT OR Apache-2.0 |
| `ebml-iterable` | MIT |
| `ebml-iterable-specification` | MIT |
| `ebml-iterable-specification-derive` | MIT |
| `enum-assoc` | MIT OR Apache-2.0 |
| `equivalent` | Apache-2.0 OR MIT |
| `errno` | MIT OR Apache-2.0 |
| `fastbloom` | MIT OR Apache-2.0 |
| `fastrand` | Apache-2.0 OR MIT |
| `flate2` | MIT OR Apache-2.0 |
| `foldhash` | Zlib |
| `form_urlencoded` | MIT OR Apache-2.0 |
| `fs-err` | MIT OR Apache-2.0 |
| `futures` | MIT OR Apache-2.0 |
| `futures-channel` | MIT OR Apache-2.0 |
| `futures-core` | MIT OR Apache-2.0 |
| `futures-executor` | MIT OR Apache-2.0 |
| `futures-io` | MIT OR Apache-2.0 |
| `futures-macro` | MIT OR Apache-2.0 |
| `futures-sink` | MIT OR Apache-2.0 |
| `futures-task` | MIT OR Apache-2.0 |
| `futures-util` | MIT OR Apache-2.0 |
| `generic-array` | MIT |
| `getrandom` | MIT OR Apache-2.0 |
| `getrandom` | MIT OR Apache-2.0 |
| `ghash` | Apache-2.0 OR MIT |
| `glob` | MIT OR Apache-2.0 |
| `goblin` | MIT |
| `h264-parser` | MIT |
| `hang` | MIT OR Apache-2.0 |
| `hashbrown` | MIT OR Apache-2.0 |
| `heck` | MIT OR Apache-2.0 |
| `hex` | MIT OR Apache-2.0 |
| `http` | MIT OR Apache-2.0 |
| `http-body` | MIT |
| `http-body-util` | MIT |
| `httparse` | MIT OR Apache-2.0 |
| `humantime` | MIT OR Apache-2.0 |
| `humantime-serde` | MIT OR Apache-2.0 |
| `hyper` | MIT |
| `hyper-util` | MIT |
| `icu_collections` | Unicode-3.0 |
| `icu_locale_core` | Unicode-3.0 |
| `icu_normalizer` | Unicode-3.0 |
| `icu_normalizer_data` | Unicode-3.0 |
| `icu_properties` | Unicode-3.0 |
| `icu_properties_data` | Unicode-3.0 |
| `icu_provider` | Unicode-3.0 |
| `ident_case` | MIT/Apache-2.0 |
| `identity-hash` | Apache-2.0 OR MIT |
| `idna` | MIT OR Apache-2.0 |
| `idna_adapter` | Apache-2.0 OR MIT |
| `indexmap` | Apache-2.0 OR MIT |
| `inotify` | ISC |
| `inotify-sys` | ISC |
| `inout` | MIT OR Apache-2.0 |
| `ipnet` | MIT OR Apache-2.0 |
| `is_terminal_polyfill` | MIT OR Apache-2.0 |
| `itoa` | MIT OR Apache-2.0 |
| `jni` | MIT OR Apache-2.0 |
| `jni-macros` | MIT OR Apache-2.0 |
| `jni-sys` | MIT OR Apache-2.0 |
| `jni-sys-macros` | MIT OR Apache-2.0 |
| `json-patch` | MIT/Apache-2.0 |
| `jsonptr` | MIT OR Apache-2.0 |
| `kio` | MIT OR Apache-2.0 |
| `lazy_static` | MIT OR Apache-2.0 |
| `libc` | MIT OR Apache-2.0 |
| `libm` | MIT |
| `linux-raw-sys` | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| `litemap` | Unicode-3.0 |
| `lock_api` | MIT OR Apache-2.0 |
| `log` | MIT OR Apache-2.0 |
| `lru-slab` | MIT OR Apache-2.0 OR Zlib |
| `matchers` | MIT |
| `memchr` | Unlicense OR MIT |
| `minimal-lexical` | MIT/Apache-2.0 |
| `miniz_oxide` | MIT OR Zlib OR Apache-2.0 |
| `mio` | MIT |
| `moq-ffi` | MIT OR Apache-2.0 |
| `moq-flate` | MIT OR Apache-2.0 |
| `moq-json` | MIT OR Apache-2.0 |
| `moq-loc` | MIT OR Apache-2.0 |
| `moq-msf` | MIT OR Apache-2.0 |
| `moq-mux` | MIT OR Apache-2.0 |
| `moq-native` | MIT OR Apache-2.0 |
| `moq-net` | MIT OR Apache-2.0 |
| `mp4-atom` | MIT OR Apache-2.0 |
| `mpeg2ts` | MIT |
| `nom` | MIT |
| `noq` | MIT OR Apache-2.0 |
| `noq-proto` | MIT OR Apache-2.0 |
| `noq-udp` | MIT OR Apache-2.0 |
| `notify` | CC0-1.0 |
| `notify-types` | MIT OR Apache-2.0 |
| `nu-ansi-term` | MIT |
| `num` | MIT OR Apache-2.0 |
| `num-bigint` | MIT OR Apache-2.0 |
| `num-complex` | MIT OR Apache-2.0 |
| `num-conv` | MIT OR Apache-2.0 |
| `num-integer` | MIT OR Apache-2.0 |
| `num-iter` | MIT OR Apache-2.0 |
| `num-rational` | MIT OR Apache-2.0 |
| `num-traits` | MIT OR Apache-2.0 |
| `num_enum` | BSD-3-Clause OR MIT OR Apache-2.0 |
| `num_enum_derive` | BSD-3-Clause OR MIT OR Apache-2.0 |
| `nutype-enum` | MIT OR Apache-2.0 |
| `oid-registry` | MIT OR Apache-2.0 |
| `once_cell` | MIT OR Apache-2.0 |
| `opaque-debug` | MIT OR Apache-2.0 |
| `openssl-probe` | MIT OR Apache-2.0 |
| `parking_lot` | MIT OR Apache-2.0 |
| `parking_lot_core` | MIT OR Apache-2.0 |
| `pastey` | MIT OR Apache-2.0 |
| `percent-encoding` | MIT OR Apache-2.0 |
| `pin-project-lite` | Apache-2.0 OR MIT |
| `plain` | MIT/Apache-2.0 |
| `polyval` | Apache-2.0 OR MIT |
| `portable-atomic` | Apache-2.0 OR MIT |
| `potential_utf` | Unicode-3.0 |
| `powerfmt` | MIT OR Apache-2.0 |
| `ppv-lite86` | MIT OR Apache-2.0 |
| `proc-macro-crate` | MIT OR Apache-2.0 |
| `proc-macro2` | MIT OR Apache-2.0 |
| `qmux` | MIT OR Apache-2.0 |
| `quote` | MIT OR Apache-2.0 |
| `rand` | MIT OR Apache-2.0 |
| `rand` | MIT OR Apache-2.0 |
| `rand_chacha` | MIT OR Apache-2.0 |
| `rand_core` | MIT OR Apache-2.0 |
| `rand_core` | MIT OR Apache-2.0 |
| `rand_pcg` | MIT OR Apache-2.0 |
| `rcgen` | MIT OR Apache-2.0 |
| `ref-cast` | MIT OR Apache-2.0 |
| `ref-cast-impl` | MIT OR Apache-2.0 |
| `regex` | MIT OR Apache-2.0 |
| `regex-automata` | MIT OR Apache-2.0 |
| `regex-syntax` | MIT OR Apache-2.0 |
| `reqwest` | MIT OR Apache-2.0 |
| `rustc-hash` | Apache-2.0 OR MIT |
| `rusticata-macros` | MIT/Apache-2.0 |
| `rustix` | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| `rustls` | Apache-2.0 OR ISC OR MIT |
| `rustls-native-certs` | Apache-2.0 OR ISC OR MIT |
| `rustls-pki-types` | MIT OR Apache-2.0 |
| `rustls-platform-verifier` | MIT OR Apache-2.0 |
| `rustls-platform-verifier-android` | MIT OR Apache-2.0 |
| `rustls-webpki` | ISC |
| `rustversion` | MIT OR Apache-2.0 |
| `same-file` | Unlicense/MIT |
| `scopeguard` | MIT OR Apache-2.0 |
| `scroll` | MIT |
| `scroll_derive` | MIT |
| `scuffle-av1` | MIT OR Apache-2.0 |
| `scuffle-bytes-util` | MIT OR Apache-2.0 |
| `scuffle-expgolomb` | MIT OR Apache-2.0 |
| `scuffle-h265` | MIT OR Apache-2.0 |
| `scuffle-workspace-hack` | MIT OR Apache-2.0 |
| `semver` | MIT OR Apache-2.0 |
| `serde` | MIT OR Apache-2.0 |
| `serde_core` | MIT OR Apache-2.0 |
| `serde_derive` | MIT OR Apache-2.0 |
| `serde_json` | MIT OR Apache-2.0 |
| `serde_path_to_error` | MIT OR Apache-2.0 |
| `serde_spanned` | MIT OR Apache-2.0 |
| `serde_with` | MIT OR Apache-2.0 |
| `serde_with_macros` | MIT OR Apache-2.0 |
| `sfv` | MIT/Apache-2.0 |
| `sha1` | MIT OR Apache-2.0 |
| `sharded-slab` | MIT |
| `signal-hook-registry` | MIT OR Apache-2.0 |
| `simd-adler32` | MIT |
| `simd_cesu8` | Apache-2.0 OR MIT |
| `simdutf8` | MIT OR Apache-2.0 |
| `siphasher` | MIT/Apache-2.0 |
| `slab` | MIT |
| `smallvec` | MIT OR Apache-2.0 |
| `smawk` | MIT |
| `socket2` | MIT OR Apache-2.0 |
| `sorted-index-buffer` | MIT OR Apache-2.0 |
| `stable_deref_trait` | MIT OR Apache-2.0 |
| `static_assertions` | MIT OR Apache-2.0 |
| `strsim` | MIT |
| `subtle` | BSD-3-Clause |
| `syn` | MIT OR Apache-2.0 |
| `syn` | MIT OR Apache-2.0 |
| `syn` | MIT OR Apache-2.0 |
| `sync_wrapper` | Apache-2.0 |
| `synstructure` | MIT |
| `synstructure` | MIT |
| `tempfile` | MIT OR Apache-2.0 |
| `textwrap` | MIT |
| `thiserror` | MIT OR Apache-2.0 |
| `thiserror` | MIT OR Apache-2.0 |
| `thiserror-impl` | MIT OR Apache-2.0 |
| `thiserror-impl` | MIT OR Apache-2.0 |
| `thread_local` | MIT OR Apache-2.0 |
| `time` | MIT OR Apache-2.0 |
| `time-core` | MIT OR Apache-2.0 |
| `time-macros` | MIT OR Apache-2.0 |
| `tinystr` | Unicode-3.0 |
| `tinyvec` | Zlib OR Apache-2.0 OR MIT |
| `tokio` | MIT |
| `tokio-macros` | MIT |
| `tokio-rustls` | MIT OR Apache-2.0 |
| `tokio-stream` | MIT |
| `tokio-tungstenite` | MIT |
| `tokio-util` | MIT |
| `toml` | MIT OR Apache-2.0 |
| `toml_datetime` | MIT OR Apache-2.0 |
| `toml_edit` | MIT OR Apache-2.0 |
| `toml_parser` | MIT OR Apache-2.0 |
| `toml_writer` | MIT OR Apache-2.0 |
| `tower` | MIT |
| `tower-http` | MIT |
| `tower-layer` | MIT |
| `tower-service` | MIT |
| `tracing` | MIT |
| `tracing-attributes` | MIT |
| `tracing-core` | MIT |
| `tracing-log` | MIT |
| `tracing-subscriber` | MIT |
| `try-lock` | MIT |
| `tungstenite` | MIT OR Apache-2.0 |
| `typenum` | MIT OR Apache-2.0 |
| `unicode-ident` | (MIT OR Apache-2.0) AND Unicode-3.0 |
| `unicode-segmentation` | MIT OR Apache-2.0 |
| `unicode-xid` | MIT OR Apache-2.0 |
| `uniffi` | MPL-2.0 |
| `uniffi_bindgen` | MPL-2.0 |
| `uniffi_core` | MPL-2.0 |
| `uniffi_internal_macros` | MPL-2.0 |
| `uniffi_macros` | MPL-2.0 |
| `uniffi_meta` | MPL-2.0 |
| `uniffi_pipeline` | MPL-2.0 |
| `uniffi_udl` | MPL-2.0 |
| `universal-hash` | MIT OR Apache-2.0 |
| `untrusted` | ISC |
| `url` | MIT OR Apache-2.0 |
| `utf8_iter` | Apache-2.0 OR MIT |
| `utf8parse` | Apache-2.0 OR MIT |
| `walkdir` | Unlicense/MIT |
| `want` | MIT |
| `web-async` | MIT OR Apache-2.0 |
| `web-transport-noq` | MIT OR Apache-2.0 |
| `web-transport-proto` | MIT OR Apache-2.0 |
| `web-transport-trait` | MIT OR Apache-2.0 |
| `webm-iterable` | MIT |
| `webpki-roots` | CDLA-Permissive-2.0 |
| `weedle2` | MIT |
| `winnow` | MIT |
| `writeable` | Unicode-3.0 |
| `x509-parser` | MIT OR Apache-2.0 |
| `yasna` | MIT OR Apache-2.0 |
| `yoke` | Unicode-3.0 |
| `yoke-derive` | Unicode-3.0 |
| `zerocopy` | BSD-2-Clause OR Apache-2.0 OR MIT |
| `zerofrom` | Unicode-3.0 |
| `zerofrom-derive` | Unicode-3.0 |
| `zeroize` | Apache-2.0 OR MIT |
| `zerotrie` | Unicode-3.0 |
| `zerovec` | Unicode-3.0 |
| `zerovec-derive` | Unicode-3.0 |
| `zmij` | MIT |
