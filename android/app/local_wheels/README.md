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
  (CPython 3.12, shared build) plus a `lib_dir` pointing at a stub
  `libpython3.12.so` (an empty placeholder ELF `.so`, just so the linker can
  resolve `-lpython3.12` at build time). At runtime on-device, the loader
  resolves that `NEEDED libpython3.12.so` entry against Chaquopy's own
  bundled `libpython3.12.so` instead — the same mechanism Chaquopy's own
  native packages (cryptography, Pillow, psutil, pyyaml) rely on.
- `pydantic-core`: built from the `pyo3` feature set with `generate-import-lib`
  removed — that feature is Windows-only (synthesizes a `.lib` import stub)
  and, left enabled, made `pyo3-build-config` still try to link against a
  nonexistent Windows-style stub even when targeting Android.

Versions here must stay in sync with `requirements.txt`'s `pydantic==` and
`openai==` pins (`pydantic-core==2.46.4` matches `pydantic==2.13.4`;
`jiter==0.17.0` satisfies openai's `jiter<1,>=0.10.0`). Rebuilding after a
version bump: download the matching sdist from PyPI, drop the
`generate-import-lib` feature from `pydantic-core`'s `Cargo.toml` if still
present, then `maturin build --release --target aarch64-linux-android
--interpreter python3.12 -o <out>` with `PYO3_CONFIG_FILE` set as above.
