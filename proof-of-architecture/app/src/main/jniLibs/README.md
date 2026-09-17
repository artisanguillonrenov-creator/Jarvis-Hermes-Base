# Native executable payload (NOT included in this PR)

This directory must contain, under `arm64-v8a/` (the only ABI this spike
targets — see `app/build.gradle.kts`), the **executable** ELF binaries and
shared libraries of the Termux-style Python bootstrap: the native Python
interpreter, the dynamic linker/loader shim, and any other bootstrap tool that
must carry the execute bit after installation.

Per the spec (§4.1): only executable ELF binaries belong here. Everything else
(`.py`/`.pyc` files, `site-packages`, package data) belongs in
`app/src/main/assets/runtime_payload/` instead — packaging it here would
needlessly bloat the APK and complicate updates.

Android's packaging pipeline only preserves the executable bit for files it
recognizes as native libraries, which is why:
- every executable in this tree must be named `lib<something>.so` (the `.so`
  extension is what makes Android's build tooling treat it as a native
  library, regardless of its actual ELF type — a real Python interpreter
  binary works fine renamed this way);
- `packagingOptions.jniLibs.useLegacyPackaging = true` is set in
  `app/build.gradle.kts` and `android:extractNativeLibs="true"` is set in the
  manifest — both are required for the executable bit to survive installation
  (see plan v2 §1.3).

**Why this is empty in this PR**: producing real `arm64-v8a`/Bionic Python
binaries requires either an existing Termux bootstrap export or a dedicated
cross-compilation toolchain. That is a binary-artifact production task, not a
code change, and this session has no Android device/emulator and no existing
bootstrap export to source them from. Per the task spec's own instruction
("documente l'écart dans la PR — ne l'improvise pas silencieusement"), this
gap is called out here and in the top-level `proof-of-architecture/README.md`
rather than filled with placeholder/fake binaries that would let
`HermesRuntime.start()` appear to work without actually doing so.
