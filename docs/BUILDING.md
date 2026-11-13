# Reproducible Android builds

The repository's canonical build is the **Android CI** workflow in
`.github/workflows/android.yml`. It runs for pushes and pull-request updates.

## Toolchain

- Gradle: **7.6.1** (currently required by the project configuration; it is end-of-life and should be upgraded in a separate compatibility change)
- JDK 8: builds the `jre_lwjgl3glfw` compatibility JAR
- JDK 17: builds the Android application
- Android NDK: selected by the existing Android Gradle configuration

Do not change the Gradle or Android Gradle Plugin versions as part of an unrelated graphics change. Upgrade them together and validate the native build.

## Clean build

Run from the repository root:

```bash
bash scripts/verify-source-integrity.sh
bash scripts/download-runtimes.sh
```

The runtime script downloads the pinned Android JRE 8 and JRE 17 release archives,
checks their SHA-256 digests, tests each ZIP, and tests the contained TAR.XZ files.
The expected runtime source URLs and digests are also recorded in
`out/runtime-sources.txt`.

With JDK 8 selected:

```bash
./scripts/languagelist_updater.sh
gradle :jre_lwjgl3glfw:build --no-daemon
```

Then select JDK 17 and build the Android APK:

```bash
gradle :app_pojavlauncher:assembleDebug --no-daemon
```

The APK is written to:

`app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`

## CI validation

Before an APK is uploaded, CI:

1. Checks the recovered Java and native source files for truncation and required symbols.
2. Validates the bundled `rt4.jar`.
3. Verifies the downloaded JRE archive checksums and archive integrity.
4. Builds the app with bundled runtimes.
5. Builds the no-runtime variant.
6. Inspects both APK ZIP structures and checks required native libraries and client assets.
7. Writes `build-info.json`, `runtime-sources.txt`, and `SHA256SUMS` beside each APK.

The build metadata records the source commit, triggering commit, workflow run URL,
generated LWJGL compatibility JAR digest, and runtime source digests. Use those
files to identify exactly which inputs produced a downloaded APK.

## Important compatibility note

The repository currently filters the APK to `arm64-v8a`. ARM32 runtime archives
and several `armeabi-v7a` native dependencies are present, but that does **not**
mean a 32-bit APK is currently produced. ARM32 packaging must be enabled and
verified as a separate build milestone.

Do not treat a successful CI build as physical-device validation. Mali and Adreno
rendering, touch input, audio, and long-session stability still require tests on
real Android hardware.
