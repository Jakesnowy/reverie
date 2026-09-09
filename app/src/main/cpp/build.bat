@echo off
setlocal EnableDelayedExpansion

REM ===========================================================================
REM Local Dream native engine build (Windows)
REM
REM Builds the C++ diffusion engine (libstable_diffusion_core.so) with CMake +
REM Android NDK and copies it, together with the QNN runtime libs, into the
REM locations the Gradle build packages into the APK (Windows counterpart of
REM build.sh, which uses CMake presets on Linux).
REM
REM Configuration - all optional environment variables, auto-detected otherwise:
REM   ANDROID_NDK_ROOT  Android NDK path        (e.g. C:\Android\ndk\android-ndk-r29)
REM   QAIRT_SDK_ROOT    Qualcomm QAIRT/QNN SDK  (e.g. C:\Android\qnn\qairt\2.39.0.250926)
REM   ANDROID_SDK_ROOT  Android SDK             (used to locate its bundled CMake)
REM   ANDROID_CMAKE     CMake executable path   (falls back to "cmake" on PATH)
REM
REM Requires: Ninja (bundled with the SDK CMake; also commonly on PATH) and git
REM (used to apply SampleApp.patch).
REM ===========================================================================

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

REM --- CMake ---------------------------------------------------------------
set "ANDROID_CMAKE_DIR="
if not defined ANDROID_CMAKE (
    if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\cmake" (
        for /d %%D in ("%ANDROID_SDK_ROOT%\cmake\*") do set "ANDROID_CMAKE_DIR=%%~fD"
    )
    if not defined ANDROID_CMAKE_DIR if exist "C:\Android\Sdk\cmake" (
        for /d %%D in ("C:\Android\Sdk\cmake\*") do set "ANDROID_CMAKE_DIR=%%~fD"
    )
    if defined ANDROID_CMAKE_DIR set "ANDROID_CMAKE=!ANDROID_CMAKE_DIR!\bin\cmake.exe"
)
if not defined ANDROID_CMAKE set "ANDROID_CMAKE=cmake"
if defined ANDROID_CMAKE_DIR set "PATH=!ANDROID_CMAKE_DIR!\bin;%PATH%"

REM --- Android NDK ---------------------------------------------------------
REM Prefer r28: engines built with NDK r29 compile fine but throw
REM std::bad_alloc at generation start on device (verified on Snapdragon
REM 8 Gen 3, SD1.5 + SDXL NPU). r28 matches the upstream Linux toolchain.
REM Both install layouts are covered: standalone folder and sdkmanager.
if not defined ANDROID_NDK_ROOT (
    for /d %%D in ("C:\Android\ndk\android-ndk-r28*") do set "ANDROID_NDK_ROOT=%%~fD"
    if not defined ANDROID_NDK_ROOT for /d %%D in ("C:\Android\Sdk\ndk\28.*") do set "ANDROID_NDK_ROOT=%%~fD"
)
if not defined ANDROID_NDK_ROOT (
    for /d %%D in ("C:\Android\ndk\android-ndk-*") do set "ANDROID_NDK_ROOT=%%~fD"
    if not defined ANDROID_NDK_ROOT for /d %%D in ("C:\Android\Sdk\ndk\*") do set "ANDROID_NDK_ROOT=%%~fD"
)
if not defined ANDROID_NDK_ROOT (
    echo [ERROR] Android NDK not found. Set ANDROID_NDK_ROOT, e.g.
    echo         set "ANDROID_NDK_ROOT=C:\Android\ndk\android-ndk-r29"
    goto :error
)

REM --- Qualcomm QAIRT (QNN) SDK ----------------------------------------------
if not defined QAIRT_SDK_ROOT (
    for /d %%D in ("C:\Android\qnn\qairt\*") do set "QAIRT_SDK_ROOT=%%~fD"
)
if not defined QAIRT_SDK_ROOT (
    echo [ERROR] QAIRT SDK not found. Set QAIRT_SDK_ROOT, e.g.
    echo         set "QAIRT_SDK_ROOT=C:\Android\qnn\qairt\2.39.0.250926"
    goto :error
)
if not exist "%QAIRT_SDK_ROOT%\lib\aarch64-android\libQnnHtp.so" (
    echo [ERROR] "%QAIRT_SDK_ROOT%" does not look like a QAIRT SDK ^(missing lib\aarch64-android\libQnnHtp.so^)
    goto :error
)

REM CMake prefers forward slashes
set "NDK_FWD=%ANDROID_NDK_ROOT:\=/%"
set "QNN_FWD=%QAIRT_SDK_ROOT:\=/%"

echo [build.bat] CMake    : %ANDROID_CMAKE%
echo [build.bat] NDK      : %ANDROID_NDK_ROOT%
echo [build.bat] QAIRT SDK: %QAIRT_SDK_ROOT%

REM --- Configure (explicit flags; CMake presets remain the Linux route) ------
"%ANDROID_CMAKE%" -S . -B build\android -G Ninja ^
 -DCMAKE_TOOLCHAIN_FILE="%NDK_FWD%/build/cmake/android.toolchain.cmake" ^
 -DCMAKE_ANDROID_NDK="%NDK_FWD%" ^
 -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a -DANDROID_ABI=arm64-v8a ^
 -DANDROID_PLATFORM=android-21 -DANDROID_NATIVE_API_LEVEL=21 -DCMAKE_SYSTEM_VERSION=21 ^
 -DCMAKE_SYSTEM_NAME=Android -DANDROID_STL=c++_static ^
 -DCMAKE_BUILD_TYPE=Release -DQNN_DEBUG_ENABLE=OFF ^
 -DQNN_SDK_ROOT="%QNN_FWD%" ^
 -DCMAKE_POLICY_VERSION_MINIMUM=3.5 ^
 -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
if %ERRORLEVEL% neq 0 goto :error

REM --- Build -----------------------------------------------------------------
"%ANDROID_CMAKE%" --build build\android
if %ERRORLEVEL% neq 0 goto :error

REM --- Install outputs where the Gradle build expects them -------------------
if not exist "..\assets\qnnlibs" mkdir "..\assets\qnnlibs"
xcopy /Y /E /I /Q build\android\qnnlibs "..\assets\qnnlibs\"
if %ERRORLEVEL% neq 0 goto :error

if not exist "..\jniLibs\arm64-v8a" mkdir "..\jniLibs\arm64-v8a"
copy /Y build\android\bin\arm64-v8a\libstable_diffusion_core.so "..\jniLibs\arm64-v8a\"
if %ERRORLEVEL% neq 0 goto :error

echo Build completed successfully
goto :eof

:error
echo Failed with error #%ERRORLEVEL%.
exit /b %ERRORLEVEL%

