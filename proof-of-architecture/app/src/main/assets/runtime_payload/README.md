# Readable (non-executable) runtime payload (NOT included in this PR)

This directory is where the readable-only parts of the Python installation
belong: `.py`/`.pyc` files, `site-packages` contents, and any Hermes/Termux
package data that only needs to be *read*, never executed directly (per spec
§4.1 — only executable ELF binaries need `jniLibs/`).

`TermuxLikeHermesRuntime.extractBootstrapIfNeeded()` extracts everything under
this asset directory into `filesDir/runtime/` on first launch, verbatim,
preserving relative paths.

Empty in this PR for the same reason `app/src/main/jniLibs/` is empty: see
that directory's `README.md` and the top-level `proof-of-architecture/README.md`
for the documented gap. This directory's structure (the extraction target
`filesDir/runtime/`, the asset root name `runtime_payload/`) is real and used
by `TermuxLikeHermesRuntime` — only its *contents* are missing.
