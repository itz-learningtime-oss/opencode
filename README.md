# OpenCode CLI for Android (armeabi-v7a)

Standalone Android client that runs an embedded OpenCode CLI over a lightweight Canvas terminal and a JNI PTY. Targets Samsung Galaxy M02-class devices: 32-bit ARM only, no root, no Termux.

The app talks only to an OpenCode Server via user-configured Base URL and Auth Token. No Gemini keys. No third-party LLM keys.

## How Node runs (important)

`nodejs-mobile` ships Node as a **shared library**, `libnode.so`, not an executable.
It cannot be started with `execve`. The app therefore:

1. Loads `libnode.so` and resolves the exported symbol `_ZN4node5StartEiPPc`
   (`node::Start(int, char**)`).
2. Allocates a PTY, `dup2`s the slave onto fd 0/1/2, and reads the master on a
   background thread into the terminal.
3. Calls `node::Start` with:

```
node --max-old-space-size=256 <filesDir>/opencode/index.js
```

This is functionally equivalent to the requested command line, with the same
`OPENCODE_SERVER_URL` / `OPENCODE_API_KEY` environment. A legacy fork/exec path
is still available if you provide a real standalone `node` executable.

## Constraints

- ABI: `armeabi-v7a` only
- minSdk 24 / targetSdk 34
- Android 10 W^X: native binaries are packaged as `.so` in `jniLibs/armeabi-v7a` and executed from `nativeLibraryDir`
- Node heap: `--max-old-space-size=256`

## Project layout

- `app/build.gradle.kts` — NDK `abiFilters "armeabi-v7a"`, no 64-bit splits
- `app/src/main/cpp/NativeTerminalJNI.c` — `posix_openpt` / `fork` PTY, JNI I/O
- `app/src/main/java/ai/opencode/cli/terminal/TerminalView.kt` — Canvas ANSI terminal
- `app/src/main/java/ai/opencode/cli/node/AssetExtractor.kt` — extract/chmod helpers
- `app/src/main/java/ai/opencode/cli/node/NodeExecutor.kt` — spawn `node` + OpenCode env
- `app/src/main/java/ai/opencode/cli/MainActivity.kt` — settings dialog + key bar

## Fetch the Node runtime

```
./scripts/fetch-nodejs-mobile.sh
```

Installs `app/src/main/jniLibs/armeabi-v7a/libnode.so` from nodejs-mobile
(`NJM_VERSION` env overrides the default `v18.20.4`).

Optional OpenCode bundle:

```
app/src/main/assets/opencode/index.js
app/src/main/assets/opencode/opencode.zip
```

If `libnode.so` is missing, the app falls back to `/system/bin/sh` and prints packaging instructions.

## Build the APK

### Option A: GitHub Actions (no local toolchain needed)

Push the repo, then open **Actions > Android APK (armeabi-v7a) > Run workflow**.
The workflow installs the SDK/NDK, fetches nodejs-mobile, builds, and uploads the
APK as the `opencode-cli-armeabi-v7a-debug` artifact. Download it, transfer to the
phone, and install (enable "Install unknown apps").

## Configure at runtime

1. Install the APK.
2. Open Settings.
3. Set `OPENCODE_SERVER_URL` (example: `http://192.168.1.10:8080`).
4. Set Auth Token / API Key.
5. Save. The CLI starts as:

```
node --max-old-space-size=256 <filesDir>/opencode/index.js
```

Environment passed into the PTY:

- `OPENCODE_SERVER_URL`
- `OPENCODE_API_KEY` / `OPENCODE_TOKEN` / `TOKEN`
- `HOME` = app `filesDir`
- `PATH` = app native lib dir + internal `bin` + `/system/bin`

### Option B: Local Android Studio / Gradle

Requires Android SDK, NDK 26.1.10909125, CMake 3.22.1, JDK 17.

```
./scripts/fetch-nodejs-mobile.sh
```

Then either open the folder in Android Studio, or install Gradle 8.7 and run:

```
gradle :app:assembleDebug
```

If you have a Gradle wrapper jar, `./gradlew :app:assembleDebug` also works.

APK output:

```
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

## Terminal keys

Overlay bar: CTRL, ALT, ESC, TAB, arrows, HOME/END, PgUp/PgDn, ^C ^D ^Z ^L.
