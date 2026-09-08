# Audit of `codereview.md` — Reverie / "Local Dream"

Every finding in `codereview.md` was re-verified against the actual source. Verdicts are grouped into four sections: **Confirmed valid**, **Valid but overstated / nuanced**, **Technically true but practically negligible**, and **Invalid**. Evidence cites the real file/line locations found during this audit (they may differ slightly from the review's line numbers).

---

## ✅ Confirmed valid

### #1 — Arbitrary file path accepted by `/upscale` via `X-Upscaler-Path` — VALID
**Evidence:** `cpp/src/main.cpp:510-516` reads the header unvalidated; `main.cpp:572` passes it straight into `qnn_runtime::createModel(upscaler_path, "upscaler")`, and `main.cpp:567-570` passes it to `upscaler::upscaleWithMnn`. In host mode the server binds `0.0.0.0:8081` with no auth (`--listen_all`, set by `BackendService` when `KEY_HOST_MODE_ACTIVE` is flipped by `RemoteHostService`).

**Audit reasoning:** This is real, and arguably *worse* than the review states: even **without** host mode, the server binds `127.0.0.1`, and Android does not isolate loopback between apps — any installed app holding only `INTERNET` can connect to `127.0.0.1:8081` and supply an arbitrary path, which is then parsed by QNN/MNN native parsers (memory-corruption surface) or used to crash/DoS the engine. The review's "read arbitrary files" framing is slightly too strong — the file's *contents* aren't directly exfiltrated (the response is an upscaled image) — but feeding attacker-chosen bytes into native parsers is a genuine vulnerability. The design is also self-inflicted: `RemoteHostService.kt:298-305` builds the catalog with absolute paths and the controller "echoes the path back", exactly as the review describes. The suggested fixes (whitelist under the models dir / opaque handle) are correct.

### #2 — Unauthenticated ports, `CORS *`, and docs that claim "authenticated" — VALID
**Evidence:**
- `RemoteHostServer.kt:22-25`: header comment explicitly admits *"No authentication, matching the trust model of the existing 'allow LAN access' feature."* No auth code exists anywhere in the file.
- `RemoteHostService.kt:39`: KDoc claims it "runs the small **authenticated** control API" — contradicted by the above.
- `RemoteProtocol.kt:10`: also says "a small **authenticated** JSON API"; worse, `RemoteProtocol.kt:40` says `/info` is "**the only unauthenticated endpoint**", which is flatly false.
- `main.cpp:776-781`: `Access-Control-Allow-Origin: *` is set as a *default header on every response*, plus a blanket `svr.Options(".*")` at `main.cpp:782-784`.

**Audit reasoning:** The factual claims all check out. The unauthenticated LAN exposure is a *documented* design decision (the `RemoteHostServer` comment is honest about it), so the security posture itself is a defensible trade-off — but the review's key point stands: the documentation actively lies to maintainers in three places, which is the cheap, unambiguous part to fix. The `CORS *` point is also correct: the Kotlin app (OkHttp) has zero need for CORS, so the only beneficiary of `*` is a browser-based attacker on the LAN, who can both issue and *read* cross-origin responses. Severity as "Critical" is defensible; the concrete recommendations (pairing token, drop CORS header, fix docs) are sound and low-cost.

### #6 — Prompts and NSFW scores written to logcat — VALID
**Evidence:** `main.cpp:398-406` prints the full prompt, negative prompt, and seed to stdout unconditionally. `SDUtils.hpp:225` prints `NSFW Score: ...` via `std::cout`. `BackendService.kt:576-584` (`startMonitorThread`) forwards *every* stdout line to `Log.i(TAG, "Backend: $line")`.

**Audit reasoning:** Fully confirmed. One calibration: on this codebase's `minSdk 28`, third-party apps **cannot** read other apps' logcat (the review's "by other apps on some builds" hedge is accurate but the practical reader is adb/bugreports — which are routinely shared for support). Since this app even ships a `LogCapture` utility that puts logs into user-shareable bug reports, prompts leaking that way is a realistic privacy issue. Gating the prompt dump behind a debug flag is the right fix; "Important" severity is fair.

### #7 — `stopBackend()` can misreport a normal stop as an error — VALID
**Evidence:** `BackendService.kt:629-654`. Exactly as quoted: `destroy()` → `waitFor(5s)` → if still alive, `destroyForcibly()` **without waiting again** → `proc.exitValue()`, which throws `IllegalThreadStateException` if the process hasn't exited (SIGKILL delivery is async; QNN teardown is a plausible >5s case). The `catch (e: Exception)` at line 645 then calls `updateState(BackendState.Error(...))` — note that unlike the monitor thread, this path does **not** go through `isLiveCrash()`, so the `stopping = true` guard set at line 634 does *not* protect it. A spurious error flash during an intentional stop is a real, reproducible-in-principle bug. The suggested fix (`waitFor` again after `destroyForcibly()`, read exit code only after confirmed exit) is correct.

### #8 — Non-atomic, collision-prone history rename — VALID
**Evidence:** `HistoryManager.kt:250-271`. Files are moved first (lines 257-262), then `dao.renameModelId(oldId, newId)` runs (line 265). A crash between the two leaves DB rows pointing at renamed/missing files. In the `newDir.exists()` branch, `file.renameTo(File(newDir, file.name))` return values are ignored, and POSIX `rename(2)` silently *overwrites* same-named targets — so renaming model A into an existing model B's history dir can destroy B's images. The suggested fix (directory-level rename with copy+delete fallback, then a transactional DB update) is appropriate.

### #11 — Malformed JSON bodies silently become `{}` — VALID (suggestion)
**Evidence:** `RemoteHostServer.kt:136-140`: `catch (_: Exception) { JSONObject() }`. A `/select` with a broken body then flows into `RemoteHostService.select()`, where `body.optString("model_id")` yields `""` and the caller gets a misleading `404 "model not found"` instead of `400 "bad request"`. Confirmed end-to-end. Minor nuance: an *empty* body also legitimately maps to `{}` (used by `/stop`), so the fix should distinguish "no body" from "malformed body".

### #12 — Hardcoded port `8081` repeated in several places — VALID (suggestion)
**Evidence:** Confirmed at `BackendService.kt:448` and `:458` (literal `"8081"` strings in the command line — note both branches pass `--port` explicitly, so the C++ default at `main.cpp:54` is currently dead-but-consistent), and `RemoteProtocol.kt:19` (`GENERATION_PORT = 8081`). The failure mode is real: if another app binds 8081, `svr.listen()` just fails and the user gets a generic backend error. Hoisting into `RemoteProtocol` and surfacing a distinct "port in use" error is a fair, low-cost improvement.

### #14 — Asset size check via `available()` — VALID (suggestion)
**Evidence:** `BackendService.kt:343-347` (qnnlibs) and `:372-373` (safety checker): `assetInputStream.use { it.available().toLong() }` compared against `targetLib.length()`. `available()` is indeed not contractually the stream length — it happens to work for `AssetManager` streams. The consequence-if-wrong (stale lib never refreshed, or needless re-copy) is mild. A stored hash or embedded size in BuildConfig would be more principled; as a suggestion, this is fine.

### #17 — Download loop lacks resume support — VALID (suggestion)
**Evidence:** `ModelDownloadService.kt:214-268` (`downloadFile`): a plain streaming GET, no `Range` header, no retry loop. The truncation guard at lines 260-266 correctly rejects partial files but throws away the bytes. For multi-GB models over mobile networks this is a real UX gap. Confirmed as described.

---

## 🟡 Valid but overstated / nuanced

### #3 — Cleartext downloads with no integrity verification — VALID, severity inflated
**Evidence:** `network_security_config.xml:3-7` does set `cleartextTrafficPermitted="true"` on the **base-config** (the localhost-only `domain-config` at lines 8-11 is redundant, not restricting). No checksum exists anywhere in `Model.kt` (searched for `sha|checksum|md5` — nothing), despite `Sha256.hpp` shipping in `cpp/src/`.

**Audit reasoning:** The two factual claims (over-broad cleartext allowance; no integrity verification) are correct, and the fix recommendations are good. But the *risk framing* needs context the review omits: the download URL is `"<baseUrl>/<fileUri>"` where `baseUrl` defaults to **`https://huggingface.co/`** (`Preferences.kt:104-107`), and the bundled catalog uses HTTPS HF paths (e.g. `Model.kt:370`). Cleartext only enters the picture if the *user* configures a custom HTTP source — in which case a MITM substituting weights is attacking a configuration the user explicitly chose. The unverified-checksum half is the more valuable finding. I'd rate this **Important**, not Critical; the `filter`-flavor observation is a nice touch and fair.

### #4 — Zip extraction: flattening + ignored `renameTo` — PARTIALLY VALID
**Evidence:** `ModelDownloadService.kt:145-147` ignores `renameTo` return values; `:276` (`unzipFile`) does `entry.name.substringAfterLast('/')`, flattening subdirectories; there is no per-entry or total extraction cap.

**Audit reasoning:** Three sub-claims, three verdicts:
1. **Flattening (valid):** `unet/x.bin` and `vae/x.bin` do silently overwrite each other, both during extraction (into `extractTempDir`) and after. Confirmed silent-corruption trap; preserving validated relative paths is the right fix.
2. **Ignored `renameTo` (technically true, low risk):** the move is `filesDir/temp_downloads/...` → `filesDir/models/...` — same internal-storage volume on Android, so the cross-filesystem failure the review leans on essentially can't occur. The codebase itself treats this as worth defending against elsewhere (the upscaler branch at `:167-171` has the `copyTo` fallback with an explanatory comment), so applying the same pattern is cheap consistency — but this is hardening, not a likely bug.
3. **No zip-bomb cap (valid):** confirmed; a malicious/custom archive can fill storage. Reasonable hardening point.

The "false Success" headline is therefore only reachable via the flattening path, not the rename path, in practice.

### #5 — `runBlocking` disk scans on the control server thread — PARTIALLY VALID
**Evidence:** `RemoteHostService.kt:152` (`/models` → `runBlocking { repository.refreshAllModels() }`) and `:173` (`/select` → `runBlocking { repository.ensureLoaded() }`) on a single-worker server (`RemoteHostServer.kt:212`, `WORKER_COUNT = 1`), with the controller's read timeout at 15s (`RemoteApiClient.kt:112`).

**Audit reasoning:** The mechanics are correctly described, but two mitigating facts are missed:
- `/select`'s `ensureLoaded()` is a **no-op once loaded** (`ModelRepository` checks `isLoaded` under a mutex), so the request-path concern there is mostly theoretical.
- The review claims "*`start()` already refreshes models when host mode begins*" — **it does not**. `RemoteHostService.onStartCommand` (lines 63-103) only starts the control server and puts `BackendService` into standby; no `refreshAllModels()` call exists. If anything, that makes the `/models` rescan *more* necessary, not less — and the code comment at `RemoteHostService.kt:150-151` explicitly says the full re-scan is deliberate so models downloaded after host mode started are visible, and that "`/models` is called rarely."

Net: a real latency/stall concern (a slow-flash rescan does block `/status` heartbeats for its duration), but it's a documented, deliberate trade-off on a rarely-called route. "Important" overstates it; a stale-snapshot-with-refresh-flag is still a reasonable improvement.

### #15 — Cancelled downloads leave temp files behind — VALID, but weaker than stated
**Evidence:** `ModelDownloadService.kt:291-296` (`cancelDownload`) cancels the job without deleting the `.tmp`. However:
- **Every new download wipes the dir first**: `startDownload` lines 118-121 do `tempDir.deleteRecursively()` before downloading.
- **`TempCleaner` does NOT run on startup** — the review asked to "confirm it runs on startup." It does not: it's a manual utility invoked only from ModelListScreen UI actions (`ModelListScreen.kt:512`, `:1868`).

**Audit reasoning:** A cancelled multi-GB `.tmp` does linger until the next download starts or the user runs "clean temp files." That's a real (minor) storage leak, and the review's hedge about `TempCleaner` resolves unfavorably. Worth fixing with a `finally { tempFile?.delete() }` in the job — note the cancellation path already has a `CancellationException` handler where this belongs.

### #9 — Hardcoded QNN SDK path and `git apply` during configure — VALID
**Evidence:** `cpp/CMakeLists.txt:8` hardcodes `set(QNN_SDK_ROOT /data/qairt/2.39.0.250926)` with no `CACHE` option, so a `-DQNN_SDK_ROOT=...` override is silently ignored. Lines 10-11 `file(COPY ...)` the SampleApp **into the source tree** (`${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp`) on every reconfigure, and lines 12-17 run `git apply --directory=app/src/main/cpp/ SampleApp.patch` from `WORKING_DIRECTORY = app/src/main/cpp` — an assumed repo-layout-relative invocation whose `RESULT_VARIABLE` is only printed (lines 18-20), never checked, so patch failure fails silently.

**Audit reasoning:** All three criticisms hold: (1) no machine but the author's can configure the build without editing the file; (2) the build mutates and dirties the source tree on every configure (the `file(COPY)` into `3rdparty/` plus whatever the patch lands on); (3) patch application is fire-and-forget — if `git apply` fails (wrong cwd, already applied), the build proceeds with an unpatched SampleApp and produces a subtly broken binary. The suggested fixes (`-DQNN_SDK_ROOT` cache variable with a clear error; vendor the patched SampleApp or apply the patch to the *copied* directory idempotently instead of via `git apply` from an assumed root) are exactly right. Valid as an "Important" build-reproducibility finding.

### #10 — God files in the UI layer — VALID (with a minor inaccuracy)
**Evidence:** `ModelListScreen.kt` = 183,653 bytes / 3,811 lines; `ModelRunScreen.kt` = 173,176 bytes / 3,439 lines. Both exist and are already partially factored into `ModelRunPages.kt` / `ModelRunSupport.kt` / `ModelRunDialogs.kt`.

**Audit reasoning:** The core claim is fully confirmed — these are enormous files. One nitpick: the review's "4,000+-line file" is slightly off (3,811 and 3,439 lines respectively — the *byte* sizes are what exceed expectations). The recommendation (continue the existing split along feature lines) matches the codebase's own trajectory. Valid.

---

## 🟢 Technically true but practically negligible

### #13 — History image filename collision — TRUE, but effectively unreachable
**Evidence:** `HistoryManager.kt:90-97`: filename is `"$timestamp.$ext"` with `timestamp = System.currentTimeMillis()`, no dedup.

**Audit reasoning:** Two saves must land in the same model's history directory within one millisecond. In this app that can't realistically happen: the native backend serializes generation behind `g_generation_mutex` (`main.cpp:387`), each generation produces one save, and generations take seconds. There is no code path that fires two `saveGeneratedImage` calls concurrently against the same model dir anywhere near the same instant. The suggested fix (append DB id) is harmless and marginally more robust, but as a defect this is theoretical.

### #18 — Cancel doesn't clear the notification — NOT DEMONSTRABLE
**Evidence:** `ModelDownloadService.kt:291-296`: `cancelDownload()` already calls `stopForeground(STOP_FOREGROUND_REMOVE)` itself.

**Audit reasoning:** The review's failure scenario ("if the cancel arrives after the service already stopped itself, a stale ongoing notification can remain") doesn't hold up: if the service already stopped itself, it did so via a path that also called `stopForeground(STOP_FOREGROUND_REMOVE)` (success path at `:184`, error paths symmetric), which removes the notification. A new `ACTION_CANCEL_DOWNLOAD` intent restarts the service, hits `cancelDownload()`, which removes the notification again regardless. If the *process* dies, the system removes the FGS notification. I could not construct a sequence in the actual code that leaves a stale ongoing notification. The suggested `notificationManager.cancel(NOTIFICATION_ID)` in a `finally` is a harmless belt-and-braces addition, but as filed, this is not a real bug.

---

## ❌ Invalid

### #16 — "Invalid `--type` silently becomes SDXL" — WRONG
**Evidence:** The review cites `main.cpp:361-366` (the `case kSdxl: default:` in `createPipeline`) — but that `default` is **unreachable for invalid input**. The argument parser at `main.cpp:233-243` already validates `--type` exhaustively: unknown/missing values hit `showHelpAndExit("Invalid --type: " + typeStr)` / `"Missing --type"` and exit before any pipeline is created. Since all four enum values (`kSd15Cpu`, `kSd15Npu`, `kSdxl`, `kAnima`) are covered by named cases, the `default:` in the switch exists only to satisfy the compiler and never executes. The prescribed fix ("validate `--type` in arg parsing and exit with a clear message") is **already implemented**. This finding is simply incorrect.

---

## Summary

| Verdict | Findings |
|---|---|
| ✅ Confirmed valid | #1, #2, #6, #7, #8, #9, #11, #12, #14, #17 |
| 🟡 Valid but overstated / nuanced | #3, #4, #5, #15, #10 |
| 🟢 True but negligible | #13 |
| ❌ Invalid / not demonstrable | #16, #18 |

**Overall assessment of the review:** high quality — roughly two-thirds of findings fully survive scrutiny with accurate code citations, and the security narrative (network-facing surface outgrew its trust model) is well-supported by #1 and #2. The main corrections: #16 is flat wrong (the validation it asks for already exists), #18's failure scenario can't occur in this code, #5 relies on a mitigation (`start()` refresh) that doesn't actually exist while ignoring that `/select`'s `ensureLoaded()` is a no-op when loaded, and #3's severity assumes a non-default (user-configured HTTP) download source. The revised priority order: **#1 and #2 first** (network attack surface + lying docs), then **#7/#8** (real state-corruption bugs), then **#3's checksum half**, then the rest as hardening.
