# Reverie / "Local Dream" — Codebase Walkthrough

## 1. What this project is

**Local Dream** (`io.github.xororz.localdream`, v2.8.1) is an Android app that runs **Stable Diffusion image generation entirely on-device**, with a unique focus on **Snapdragon NPU (Hexagon) acceleration** via Qualcomm's QNN SDK. It also supports CPU (via Alibaba MNN) and GPU (OpenCL) inference. It supports **SD1.5** (NPU on Hexagon V68+), **SDXL** (NPU on Snapdragon 8 Gen 3+), plus upscaling (Real-ESRGAN / UltraSharp), inpainting, history management, and a device-to-device "remote host" mode.

## 2. Tech stack & how to build/run

- **UI:** Kotlin + Jetpack Compose (Material 3 **Expressive**), Navigation Compose
- **Engine:** C++17 native **executable** (not a JNI .so) built with CMake + Android NDK, arm64-v8a only
- **Inference backends:** Qualcomm QNN (NPU), MNN (CPU/OpenCL)
- **Support libs (C++):** cpp-httplib (local HTTP server), tokenizers-cpp, xtensor/xsimd (tensor math), stb (images), zstd (model decompression), nlohmann/json
- **Support libs (Kotlin):** OkHttp, Coil, Room (DB), KSP, ktlint + detekt
- **Build:** Gradle with version catalog; Java 17 target (build with **JDK 21** — newer JDKs break AGP's `JdkImageTransform`); `minSdk 28`, `targetSdk 36`; two product flavors — **`basic`** and **`filter`** (adds content filtering). **Debug builds install alongside release builds** (`applicationIdSuffix = ".debug"`, launcher label "Local Dream Debug").
- **Native engine:** requires the Qualcomm QAIRT (QNN) SDK 2.39.0.250926 — its path is overridable (`-DQNN_SDK_ROOT=...`); the build patches Qualcomm's SampleApp in-tree and links the Hexagon stub/skel libs.
  - **Linux:** `app/src/main/cpp/build.sh` (CMake presets; NDK r28 at `/data/android-ndk-r28`; ccache optional — auto-detected).
  - **Windows:** `app/src/main/cpp/build.bat` (self-configuring: override `ANDROID_NDK_ROOT` / `QAIRT_SDK_ROOT` / `ANDROID_SDK_ROOT` / `ANDROID_CMAKE`, otherwise auto-detected; uses the SDK's bundled CMake ≥3.31 and prefers **NDK r28**).
  - **Use NDK r28, not r29:** engines built with r29 compile cleanly but throw `std::bad_alloc` at generation start on device (verified on Snapdragon 8 Gen 3, SD1.5 + SDXL NPU); r28 matches the upstream toolchain and works.
  - After building, the script installs the engine into `app/src/main/jniLibs/arm64-v8a/` and the QNN runtime libs into `app/src/main/assets/qnnlibs/` for the Gradle build to package (arm64-v8a only).
- Run via `./gradlew` (there are `build.sh`/`build.bat` helpers in `app/src/main/cpp`).

## 3. Architecture — the key insight

The app uses an unusual but clever **"app + local HTTP server" split**:

```
┌─────────────────────────── Kotlin/Compose App ───────────────────────────┐
│  MainActivity → NavHost (ModelList, ModelRun, Upscale, History, Remote)  │
│        │ OkHttp over localhost:8081                                      │
├────────┼─────────────────────────────────────────────────────────────────┤
│  BackendService (foreground service)                                     │
│    copies native binary to runtime dir and spawns it as a subprocess:    │
│    libstable_diffusion_core.so  (an EXECUTABLE disguised as a .so)       │
└────────┼─────────────────────────────────────────────────────────────────┘
         ▼
┌──────────────────────────── C++ Engine (main.cpp) ───────────────────────┐
│  cpp-httplib server on 127.0.0.1:8081 (0.0.0.0 in host mode)             │
│  POST /generate   POST /upscale   POST /tokenize   GET /health           │
│  Pipeline variants: PipelineSd15Cpu / PipelineSd15Npu / PipelineSdxl /   │
│  PipelineAnima; QnnModel/QnnRuntime (NPU), MNN (CPU), schedulers,        │
│  text encoders, tokenizers, tiling, Laplacian blending, upscaler         │
└──────────────────────────────────────────────────────────────────────────┘
```

The native binary is packaged as `libstable_diffusion_core.so` inside the APK's jniLibs so Android ships it, then extracted to a `runtime_libs` dir and **executed as a process** (using the `EXECUTE_PRIVATE_BINARY` permission) — this isolates crashes and avoids JNI overhead.

## 4. Directory structure

```
app/src/main/
├── java/io/github/xororz/localdream/
│   ├── MainActivity.kt / LocalDreamApplication.kt   # entry, permissions, data migration gate
│   ├── data/          # Model.kt (40KB - model catalog), HistoryManager, Preferences,
│   │                  # TagAutocompleteRepository (danbooru-style tag suggestions), db/ (Room)
│   ├── service/       # BackendService (spawns/manages native process w/ reconcile
│   │                  # + 1.5s idle-grace reuse), BackgroundGenerationService,
│   │                  # ModelDownloadService, RemoteHostService
│   ├── remote/        # RemoteApiClient/HostServer/Protocol - control one phone from another
│   ├── ui/screens/    # ModelRunScreen (173KB!), ModelListScreen (183KB), InpaintScreen,
│   │                  # UpscaleScreen, HistoryScreen, CropImageScreen...
│   ├── ui/theme/      # MD3 Expressive theme, preset color schemes, motion
│   ├── ui/components/ # PromptTagTextField, dialogs, zoomable image overlay
│   ├── navigation/    # Screen routes
│   └── utils/         # ImageUtils, LogCapture, ParamShare, TempCleaner
├── cpp/
│   ├── CMakeLists.txt # QNN SDK integration, MNN, tokenizers, links everything
│   └── src/           # ~35 header-only engine files (see below)
└── assets/cvtbase/    # bundled tokenizer/CLIP assets
```

## 5. The C++ engine (`cpp/src/`) — the heart

Header-only C++ where the notable pieces are:

| File | Role |
|---|---|
| `main.cpp` | CLI parsing, HTTP endpoints, pipeline lifecycle |
| `Pipeline.hpp` / `PipelineSd15*` / `PipelineSdxl` | Full diffusion loops; tiled generation for large images |
| `QnnModel.hpp` / `QnnRuntime.hpp` | QNN graph loading/exec on Hexagon NPU (V68–V81) |
| `SafeTensor2MNN.hpp` / `SafeTensorReader.hpp` | Parse safetensors, convert to MNN tensors (zstd-compressed) |
| `DPMSolverMultistepScheduler`, `Euler*Scheduler`, `LCMScheduler`, `FlowMatchScheduler` | Samplers |
| `TextEncoder.hpp`, `PromptProcessor.hpp`, `PromptCache.hpp` | CLIP encoding + prompt-weight handling |
| `Tiling.hpp`, `LaplacianBlend.hpp`, `Upscaler.hpp` | Tiled generation/repair ("UltraFix"), seamless blending, ESRGAN upscaling |
| `SDStructure.hpp` (130KB) | Model architecture definitions |
| `LoraMapping.hpp` | LoRA weight mapping/injection |

## 6. Patterns & conventions

- **StateFlow-driven UI state**; backend lifecycle uses a *desired vs. serving config* reconciliation pattern on a single-threaded dispatcher (well-commented concurrency reasoning throughout).
- **Immutability-friendly Kotlin**, ktlint + detekt enforced in CI (`.github/`).
- Comments are unusually high quality — e.g., `BackendService.kt` documents the race conditions it avoids; `Pipeline.hpp` explains NPU quantization quirks (time_ids saturating at 1024 calibration ceiling).
- Room DB only for history; other prefs are DataStore/JSON (`Preferences.kt`).
- The native engine is intentionally **transport-agnostic** (HTTP JSON + binary endpoints), which is what makes remote-host mode (phone-as-NPU-server) nearly free.

## 7. Where to start for common tasks

- **Change generation behavior/sampling:** `app/src/main/cpp/src/Pipeline*.hpp`, schedulers
- **Add UI screen/param:** `ui/screens/ModelRunScreen.kt` (+ `ModelRunPages.kt`, `ModelRunSupport.kt`), route in `navigation/Navigation.kt` and `MainActivity.AppContent`
- **Model catalog/formats:** `data/Model.kt`, `data/GenerationDefaults.kt`, `data/ModelConfig.kt`
- **Backend process logic:** `service/BackendService.kt`; endpoints in `cpp/src/main.cpp`
- **Download/remote features:** `service/ModelDownloadService.kt`, `remote/*`

**Summary:** A well-engineered on-device Stable Diffusion Android app whose defining design is a Kotlin/Compose front-end talking over localhost HTTP to a spawned C++ diffusion engine that targets Qualcomm's Hexagon NPU, CPU, and GPU backends — enabling both fast local generation and phone-to-phone distributed inference. The main caveats for building: Linux/Windows host with the QNN SDK path configured, and arm64-v8a-only output.

