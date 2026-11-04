# Flowseal Android module

`WsProxyProvider` implements RouteGram's existing `ProxyProvider` contract. The
module embeds the original [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)
package without modifying its sources. The app starts a loopback MTProto
listener automatically; users do not install Python or configure servers.

`upstream.json` pins Flowseal, CPython, Android NDK, and the pure-Python runtime
dependencies. Source archives and wheels are verified before packaging.
The archived Rust submodule has been removed from the dependency list. An old
local checkout may remain ignored for rollback; fresh checkouts do not fetch it.

## Build

Requirements: the project's Java/Android build tools, a host Python command, and
Docker with Linux containers. The Docker build installs its compiler packages
inside the image; it does not install Python packages into the host interpreter.

```powershell
python routegram/libtgwsproxy/tools/build.py
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Gradle runs the first command automatically when its inputs change. Override the
host command with `-ProutegramPython=C:/path/to/python.exe`. Build-only caches
live in the `routegram-python-build` Docker volume. AAR inputs are generated in
`build/pythonRuntime`; native binaries must not be committed.

The default ABIs are arm64-v8a, armeabi-v7a and x86_64. RouteGram and this module
require Android 9 (API 28), as requested for 0.2.0. Native CPython libraries
retain their API 21 build baseline; they also run on API 28 and newer.
`-ProutegramProxyAbis=x86_64` is available for a quick emulator test; omit it for
the release build. Every generated shared library is checked for 16 KB segment
alignment and resolvable native dependencies. Results are saved as `*-elf.json`.

## Runtime ownership

The small `src/main/c/python_bridge.c` adapter initializes CPython with the
checked `Py_InitializeFromConfig` API and exposes three C functions through JNA.
It handles the GIL, returns errors without calling `PyErr_Print`, and retains the
interpreter for the app process lifetime. There is no `Py_Finalize` while worker
threads can still exist.

`routegram_runtime.py` locates Android-packaged extension modules and OpenSSL,
uses the bundled certifi CA set, and starts/stops upstream's asyncio worker.
It waits for the actual listener before reporting success and refuses a second
instance if the previous worker did not stop. Secrets are redacted from logs.
The upstream Python files themselves are copied byte-for-byte from the pinned
archive. Python and native libraries are bundled in the APK; only upstream's
existing domain-list refresh happens at runtime.

Native libraries must be extracted by Android (`extractNativeLibs=true`,
`useLegacyPackaging=true`). The runtime's data files are installed under
`noBackupFilesDir/flowseal/<manifest hash>`, after their SHA-256 checks pass.
The stdlib ZIP is intentionally uncompressed: it must load `encodings` before
the Android extension loader can load `zlib`.

## Update and verify

1. Change the Flowseal version, commit and archive SHA-256 in `upstream.json`.
   CPython and wheel pins can be updated independently.
2. Rebuild. No changes to Telegram's network code are needed.
3. Run the Android tests; the expected versions are read from the pin file:

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :routegram:libtgwsproxy:connectedDebugAndroidTest
```

Tests check the AES known-answer vector, occupied-port failure, three
start/stop cycles, and a real unauthenticated MTProto `req_pq_multi`/`resPQ`
exchange through the embedded proxy with nonce validation. A separate test
resolves Telegram's WebSocket endpoint, selects it through the upstream DC
mapping option, and checks that the WS counter increases with no TCP fallback.
They require an internet connection, but no Telegram account. Run on API 28 and a device with
16 KB memory pages. This does not substitute for testing messages/media and
network changes in the full app under the user's actual blocking conditions.
Use a dedicated test device or emulator: the runner needs local port 1443 free.

For a diagnostic probe while RouteGram is running, build and install the test
APK, then invoke only this method on the device selected above:

```powershell
adb -s "$env:ANDROID_SERIAL" shell am instrument -w -r -e class 'com.routegram.wsproxy.FlowsealRuntimeTest#testIndependentRoute' com.routegram.wsproxy.test/android.test.InstrumentationTestRunner
```

It measures the two existing connectivity probes and performs the MTProto
exchange on port 1444. It does not change the installed client's guard or proxy.
Failure under an allowlist-only network means the required route is unavailable;
it does not, by itself, demonstrate a reconnection bug. Router-assisted Wi-Fi
can validate lifecycle behavior but cannot prove standalone circumvention.

The adapter touches upstream's `_run`, `_server_instance`, and `_refresh_stop`;
diagnostics also read `ws_pool._idle` to check actual WebSocket readiness.
These are private lifecycle interfaces: their compatibility is checked during
an update. Protocol code is not forked into the adapter.
