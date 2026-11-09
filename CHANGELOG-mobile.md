# 2009Scape Mobile — change log (DaveRune / Sammy-Salt fork)

Debug builds and crash logs from Nov 2026 (Motorola kansas / Mali mt6835, Android 16).

## Summary

Goal: playable low-end Android client with DaveRune high options kept, offline single-player hooks, and reliable LWJGL/gl4es on Mali.

## Fixes landed in tree

### LWJGL version alignment
- Client jar shipped 3.3.3 Java classes; Android natives are **3.2.3 SNAPSHOT**.
- Mismatch caused `Incompatible Java and native library versions` and SIGSEGV / `NoClassDefFoundError` on SD.
- **Fix:** launch with `-cp` (LWJGL 3.2.3 jars first, then `rt4.jar`) and main class `rt4.client` instead of `-jar`.
- Ship matching `lwjgl*.jar` under `assets/components/lwjgl3/` (version file bumped to force re-unpack).
- Set `-Dorg.lwjgl.librarypath` to the app `nativeLibraryDir`.

### Play SD null window crash
- JavaGUI path never called `setupBridgeWindow`; `pojavInit` → `ANativeWindow_acquire(NULL)`.
- **Fix:** Play SD starts `MainActivity` (same GLFW surface as HD) with intent extra `low_end_preset=true`.
- Low-end preset caps internal scale at **40%** and forces `opengles2`.
- `pojavInit` null-guards the window and returns 0 instead of hard-crashing.

### Mali glTexImage2D SIGSEGV (HD / GL path)
- Crash in `libGLES_mali.so` via `libgl4es_114.so` after GLBridge surface switch.
- **Mitigation (env):**
  - `LIBGL_FB=2`
  - `LIBGL_SHRINK=0`
  - `LIBGL_NOTEXSUBIMAGE=1`
  - `LIBGL_COPY=1`
  - `LIBGL_NOERROR=1`
  - `LIBGL_USEPBO=0`
  - existing: `LIBGL_MIPMAP=3`, `LIBGL_NORMALIZE=1`, `LIBGL_ES=2`

### Build / packaging
- `abiFilters "arm64-v8a"` only (smaller APK, less RAM during build).
- `getLWJGL3ClassPath` guarded against empty jar dir.
- Public helper `Tools.getLWJGL3ClassPathPublic()` for JavaGUI fallback.

## Known remaining issues
- Mali driver may still fault on some texture uploads; further gl4es / renderer work may be needed.
- Offline single-player server download + embedded JRE path is scaffolded in earlier debug APKs; keep testing on device.
- Save-file export to user-chosen folder (SAF) not finished in this commit set.

## Test notes
- Device under test: Motorola (kansas), Mali mt6835, Android 16 / SDK 36.
- Install debug APK over previous builds (same `net.kdt.pojavlaunch.debug` package).
- Prefer **Play SD** first after this change (now GLFW + low scale), then **Play HD**.
- Use in-app debug log export to `Download/` when ADB is unavailable.

## Related releases
- `sd-classpath-20261108` — classpath / LWJGL 3.2.3 alignment
- `sd-glfw-mali-20261109` — SD→MainActivity + Mali LIBGL harden (build artifact)
