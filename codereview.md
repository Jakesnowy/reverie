# Code Review — Reverie / "Local Dream"

**Scope:** Services & backend lifecycle (`BackendService.kt`, `ModelDownloadService.kt`, `RemoteHostService.kt`), remote host/server (`RemoteHostServer.kt`, `RemoteApiClient.kt`), native engine entry point (`cpp/src/main.cpp`), data layer (`HistoryManager.kt`, `HistoryFilter.kt`), and build config (`CMakeLists.txt`, `network_security_config.xml`).

**Overall:** High-quality codebase with unusually good comments explaining concurrency decisions. The backend process reconcile pattern, SQLite IN-clause chunking, and download-truncation guard show real production hardening. The findings below are the exceptions to that standard.

---

## 🔴 Critical

### 1. Arbitrary file path accepted over the network by `/upscale` (`X-Upscaler-Path`)
**File:** `app/src/main/cpp/src/main.cpp:510–516, 527–531, 570–576`

The `/upscale` endpoint reads the model path **directly from a request header** and loads it with QNN/MNN. With host mode active the server binds `0.0.0.0` with **no authentication**, so any device on the LAN can make the host:

```cpp
std::string upscaler_path = req.get_header_value("X-Upscaler-Path");
...
tempUpscalerApp = qnn_runtime::createModel(upscaler_path, "upscaler");
```

- Read arbitrary files from app-private storage (any path the app can access) and feed them to a model parser — malformed/crafted input hits native parsers with no sandboxing beyond the Android app sandbox.
- At minimum, validate the path: resolve it and require it to be under the app's models directory and to match a known upscaler ID. Better: replace the "echo the absolute path back" protocol with a short-lived opaque handle issued by the control API.

### 2. Unauthenticated, CORS-wide-open generation port; docs claim "authenticated"
**Files:** `cpp/src/main.cpp:776–778`, `remote/RemoteHostServer.kt:22–25`, `service/RemoteHostService.kt:39`

`RemoteHostService`'s KDoc says host mode runs "the small **authenticated** control API", but `RemoteHostServer` implements **no authentication** (its own header comment admits this). Meanwhile the native backend sets:

```cpp
svr.set_default_headers({{"Access-Control-Allow-Origin", "*"}, ...});
```

Combined with `--listen_all` (0.0.0.0:8081), any LAN peer — and, thanks to `CORS *`, any website open in a browser on a LAN device (cross-origin POST) — can list models and run unbounded generations (NPU/battery/heat drain). "LAN is trusted" is a defensible model (documented in `RemoteHostServer`), but:

- Fix the contradictory doc comment in `RemoteHostService.kt:39` so maintainers don't assume auth exists.
- Add a pairing token: the host displays a code, the controller sends `Authorization: Bearer <code>` on both ports, and drop `Access-Control-Allow-Origin: *` (the Kotlin app doesn't need CORS).

### 3. Model downloads over cleartext HTTP with no integrity verification
**Files:** `app/src/main/res/xml/network_security_config.xml`, `service/ModelDownloadService.kt:214–268`

`cleartextTrafficPermitted="true"` is set in the **base config** (all domains), not just localhost. Model weights (multi-GB, from third-party hosts) are downloaded over possibly-HTTP URLs with no checksum — a MITM can substitute poisoned weights that are then parsed by native C++ code. The project already ships `Sha256.hpp`. Recommendations:

- Scope cleartext to localhost only; require HTTPS for remote model URLs.
- Pin a SHA-256 in the model catalog (`data/Model.kt`) and verify the archive before extraction. The `filter` flavor in particular should never install unverified weights alongside its safety checker.

## 🟡 Important

### 4. Zip extraction drops directory structure and ignores failed renames → false "Success"
**File:** `service/ModelDownloadService.kt:145–148, 270–289`

```kotlin
extractTempDir.listFiles()?.forEach { file ->
    file.renameTo(File(modelDir, file.name))   // return value ignored
}
...
val fileName = entry.name.substringAfterLast('/')   // flattens subdirs
```

- `renameTo` returns `false` on cross-filesystem moves; the code then reports `DownloadState.Success` with an empty/partial model dir. The upscaler branch (lines 167–171) already does this right with a `copyTo` fallback — apply the same pattern here.
- `substringAfterLast('/')` flattens the archive: `unet/x.bin` and `vae/x.bin` silently overwrite each other. Model zips are flat today, but this is a silent-corruption trap; preserve validated relative paths instead of stripping them.
- No per-entry or total extraction cap (zip-bomb / storage-fill risk). Cap extracted bytes and validate against the catalog size before extracting.

### 5. `runBlocking` disk scans inside the single-threaded control server
**File:** `service/RemoteHostService.kt:152, 173`

```kotlin
runBlocking { repository.refreshAllModels() }   // /models handler
runBlocking { repository.ensureLoaded() }       // /select handler
```

The control server has exactly one worker thread (`WORKER_COUNT = 1`, deliberate). A full model-directory rescan (large NPU dirs, slow flash) blocks it for the whole scan, stalling `/status` heartbeats; the controller's read timeout is only 15s (`RemoteApiClient.kt:112`). `start()` already refreshes models when host mode begins — prefer a cached snapshot on the request path (return stale data with a `refreshing` flag) or move the refresh off the server thread.

### 6. Prompts and NSFW scores written to logcat
**Files:** `cpp/src/main.cpp:398–406`, `cpp/src/SDUtils.hpp:225`, `service/BackendService.kt:582`

`main.cpp` logs the full prompt, negative prompt, and seed to stdout; the backend monitor thread forwards **every stdout line** to `Log.i("Backend", ...)`:

```kotlin
while (reader.readLine().also { line = it } != null) {
    Log.i(TAG, "Backend: $line")
}
```

Prompts are frequently sensitive; they end up in the system log (readable via adb, and by other apps on some builds). Gate the prompt dump behind a debug/`--verbose` flag; keep step counters only by default.

### 7. `stopBackend()` can misreport a normal stop as an error
**File:** `service/BackendService.kt:629–654`

```kotlin
if (!proc.waitFor(5, TimeUnit.SECONDS)) {
    proc.destroyForcibly()
}
Log.i(TAG, "process end, code: ${proc.exitValue()}")   // may still be alive
```

`exitValue()` throws `IllegalThreadStateException` if the process hasn't exited yet (SIGKILL delivery is asynchronous; QNN teardown can exceed 5s). The `catch` then sets `BackendState.Error(...)`, flashing a spurious error in the UI during an intentional stop. `waitFor` again after `destroyForcibly()` (or use `onExit().get()`) and read the exit code only after confirmed exit.

### 8. Non-atomic, collision-prone history rename
**File:** `data/HistoryManager.kt:250–271`

`renameModel` moves files first, then updates DB rows; a crash in between leaves DB rows pointing at missing files. `file.renameTo(File(newDir, file.name))` also silently overwrites same-named files in an existing `newDir`, and per-file results are ignored. Do the directory-level rename first (fall back to copy+delete), then `renameModelId` in a transaction.

### 9. Build depends on a hardcoded QNN SDK path and `git apply` during configure
**File:** `app/src/main/cpp/CMakeLists.txt:8, 10–18`

```cmake
set(QNN_SDK_ROOT /data/qairt/2.39.0.250926)
file(COPY ${QNN_SDK_ROOT}/examples/... DESTINATION .../3rdparty/SampleApp)
execute_process(COMMAND git apply --directory=app/src/main/cpp/ ...)
```

No machine except the author's can configure this: the SDK path is user-specific, and `git apply` runs from an assumed checkout root while mutating (and dirtying) the source tree on every reconfigure. Accept `-DQNN_SDK_ROOT=` as a cache variable with a clear error, and vendor the patched SampleApp (or apply the patch idempotently to the copied directory, not via `git apply` from the repo root).

### 10. God files in the UI layer
**Files:** `ui/screens/ModelListScreen.kt` (~183 KB), `ui/screens/ModelRunScreen.kt` (~173 KB)

These single files are larger than the entire native engine's entry point plus several pipelines combined, and are already partially factored (`ModelRunPages.kt`, `ModelRunSupport.kt`, `ModelRunDialogs.kt`). Any generation-flow change touches a 4,000+-line file. Continue the existing split: extract the params panel, sampler picker, model cards, and download flow into per-feature files.

## 🟢 Suggestions

### 11. Malformed JSON bodies silently become `{}`
**File:** `remote/RemoteHostServer.kt:136–140` — a JSON parse failure yields an empty `JSONObject`, so `/select` with a broken body fails later with a confusing "missing model_id"-style error instead of `400 bad request`. Return `400` on parse failure.

### 12. Hardcoded port `8081` repeated in three places
**Files:** `service/BackendService.kt:447–448, 457–458`, `cpp/src/main.cpp:54`, `remote/RemoteProtocol.kt` (`GENERATION_PORT`). If the port is ever taken by another app, the failure surfaces only as a generic backend error. Hoist to `RemoteProtocol` and surface "port in use" distinctly.

### 13. History image filenames can collide
**File:** `data/HistoryManager.kt:90–97` — the filename is `System.currentTimeMillis()`; two saves in the same millisecond overwrite each other. Append the DB id (save after insert) or a short UUID suffix.

### 14. Asset size check via `available()`
**Files:** `service/BackendService.kt:345–347, 372–373` — `InputStream.available()` is not contractually the total asset size. It works for `AssetManager` streams in practice, but a stale/incomplete copy would then never refresh. Consider comparing a stored hash or embedding expected sizes in BuildConfig.

### 15. Cancelled downloads leave temp files behind
**File:** `service/ModelDownloadService.kt:291–296` — `cancelDownload()` cancels the job and returns to `Idle`, but a partial multi-GB `.tmp` file stays in `temp_downloads` until some later run wipes the directory (`TempCleaner` mitigates this — confirm it runs on startup).

### 16. Invalid `--type` silently becomes SDXL
**File:** `cpp/src/main.cpp:361–366` — `case kSdxl: default:` means a typo'd or unknown `--type` runs the SDXL pipeline and then fails with confusing "file not found" errors. Validate `--type` in arg parsing and exit with a clear message.

### 17. Download loop lacks resume support
**File:** `service/ModelDownloadService.kt:214–268` — multi-GB models over mobile networks will fail mid-transfer; the truncation guard correctly rejects partial files but the user restarts from byte 0. Support `Range` requests with `.tmp` resume, or at minimum retry automatically with exponential backoff.

### 18. `ModelDownloadService` cancel doesn't clear the notification
**File:** `service/ModelDownloadService.kt:291–296` — `stopForeground(STOP_FOREGROUND_REMOVE)` is called, but if the cancel arrives after the service already stopped itself, a stale ongoing notification can remain. Post a final "cancelled" state via `notificationManager.cancel(NOTIFICATION_ID)` in the job's `finally`.

---

## Summary

The codebase is in good shape for its complexity: deliberate single-threaded reconciliation for the backend process, defensive download verification, chunked SQLite queries, and exceptional comment quality. Most findings cluster around one theme — **the network-facing surface (host mode + native HTTP server) grew faster than its trust model**. Nothing looks acutely exploitable today beyond the LAN, but the LAN surface is real (hotels, cafés, shared Wi-Fi).

**Most important actions, in order:**
1. Validate/whitelist `X-Upscaler-Path` (or replace it with an opaque handle) — #1
2. Pairing token for host mode + remove `CORS *` + fix the "authenticated" doc comment — #2
3. HTTPS-only downloads + SHA-256 verification of model archives — #3
4. Honor `renameTo` results and preserve zip paths in model install — #4
5. Move `runBlocking` model scans off the control-server request path — #5
6. Stop logging prompts to logcat — #6


