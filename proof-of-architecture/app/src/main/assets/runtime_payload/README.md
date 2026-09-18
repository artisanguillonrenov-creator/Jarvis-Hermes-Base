# Readable (non-executable) runtime payload

This directory is where the readable-only parts of the Python installation
belong: `.py`/`.pyc` files, `site-packages` contents, and any Hermes/Termux
package data that only needs to be *read*, never executed directly (per spec
§4.1 — only executable ELF binaries need `jniLibs/`).

`TermuxLikeHermesRuntime.extractBootstrapIfNeeded()` extracts everything under
this asset directory into `filesDir/runtime/` on first launch, verbatim,
preserving relative paths, expecting a `hermes-src/` directory at its root
holding a full Hermes source checkout (so `pip install -e ".[termux]"` has
something to install from `filesDir/runtime/hermes-src`).

**Partially populated as of the "application installable" revision**:
`.github/workflows/build-debug-apk.yml` now actually builds the Hermes web
dashboard (`npm run build --workspace web`, which Vite already configures to
output straight to `hermes_cli/web_dist/`) and stages it here at
`hermes-src/hermes_cli/web_dist/` before every APK build — a real built
artifact, not a placeholder.

**Still empty**: everything else `hermes-src/` needs — `agent/`,
`hermes_cli/*.py`, `tui_gateway/`, `gateway/`, `pyproject.toml`, etc. Without
the rest of the source tree, `pip install -e ".[termux]"` has nothing to
install regardless of `web_dist` being present, and the native
arm64-v8a/Bionic Python interpreter under `jniLibs/` (see that directory's
`README.md`) still doesn't exist either. See the top-level
`proof-of-architecture/README.md` for the full, current state of this gap.
