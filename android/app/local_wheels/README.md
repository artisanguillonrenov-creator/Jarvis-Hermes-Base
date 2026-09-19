# Locally cross-compiled Android wheels

`pydantic-core` and `jiter` are Rust/PyO3 extensions. Neither PyPI nor
Chaquopy's supplementary Android wheel repo (`https://chaquo.com/pypi-13.1`)
publishes an `android_24_arm64_v8a` build of either, so they are built here
directly with `maturin` cross-compiling to `aarch64-linux-android` (API 24,
matching Chaquopy's own Python 3.12 build):

- Toolchain: `rustup target add aarch64-linux-android`, linked via the
  Android NDK's `aarch64-linux-android24-clang` (set in `~/.cargo/config.toml`
  for the `aarch64-linux-android` target).
- PyO3 cross config: a `PYO3_CONFIG_FILE` describing the target interpreter
  (CPython 3.12, shared build) plus a `lib_dir` pointing at the **real**
  `libpython3.12.so` extracted from this app's own built APK
  (`lib/arm64-v8a/libpython3.12.so`) — NOT an empty stub. An empty stub
  linked fine (no `-z defs`/`--no-undefined` is passed) but silently broke
  at runtime: since the stub defines none of the actual CPython C-API
  symbols pydantic-core/jiter reference, the linker's `--as-needed` flag
  (present in every `rustc`-generated link line) drops the `NEEDED
  libpython3.12.so` entry entirely when nothing was actually resolved
  against it, so the built `.so` ends up with NO dependency on libpython at
  all — `readelf -d <file> | grep NEEDED` must show `libpython3.12.so`.
  On-device this surfaced as `dlopen failed: cannot locate symbol
  "PyTuple_Type"` rather than a link error, since the missing NEEDED entry
  only matters at load time. Linking against the real extracted library
  fixes this because real symbols get pulled from it, so `--as-needed` keeps
  the dependency. At runtime the loader then resolves that same `NEEDED
  libpython3.12.so` entry against Chaquopy's own bundled copy already
  loaded in the process — the same mechanism Chaquopy's own native packages
  (cryptography, Pillow, psutil, pyyaml) rely on.
- `pydantic-core`: built from the `pyo3` feature set with `generate-import-lib`
  removed — that feature is Windows-only (synthesizes a `.lib` import stub)
  and, left enabled, made `pyo3-build-config` still try to link against a
  nonexistent Windows-style stub even when targeting Android.

**Extension filename**: maturin names the built `.so` after the full target
triple (`_pydantic_core.cpython-312-aarch64-android-android.so`). Chaquopy's
own Android wheels (checked against its `psutil`/`cryptography` builds) use a
bare `<name>.so` instead — its CPython 3.12 build's `EXTENSION_SUFFIXES`
doesn't include the tagged form, so a triple-tagged `.so` is silently
invisible to the import system (`ImportError: No module named
'pydantic_core._pydantic_core'`, not a link/symbol error, since the file is
never even considered a candidate). Both wheels here have the compiled
extension renamed to the bare form after building (`_pydantic_core.so`,
`jiter.so`) with the `RECORD` hash/size updated to match — a rebuild after a
version bump must repeat that rename, not just re-run maturin.

Versions here must stay in sync with `requirements.txt`'s `pydantic==` and
`openai==` pins (`pydantic-core==2.46.4` matches `pydantic==2.13.4`;
`jiter==0.17.0` satisfies openai's `jiter<1,>=0.10.0`). Rebuilding after a
version bump: download the matching sdist from PyPI, drop the
`generate-import-lib` feature from `pydantic-core`'s `Cargo.toml` if still
present, then `maturin build --release --target aarch64-linux-android
--interpreter python3.12 -o <out>` with `PYO3_CONFIG_FILE` set as above.
