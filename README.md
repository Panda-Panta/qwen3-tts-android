# Qwen3 TTS Android

Android app for on-device Qwen3-TTS 0.6B Base using llama.cpp. This build
installs as `com.qwen.tts.android.llama`.

Qwen3 TTS Android runs the speech model on the device and provides a workflow
for cloning voices from reference recordings, previewing generated audio,
and saving WAV files manually.

## Upstream UI screenshots

| Studio | Voices |
| --- | --- |
| ![Studio screenshot](docs/screenshots/studio.jpg) | ![Voices screenshot](docs/screenshots/voices.jpg) |

| History | Settings |
| --- | --- |
| ![History screenshot](docs/screenshots/history.jpg) | ![Settings screenshot](docs/screenshots/settings.jpg) |

## Features

- On-device Qwen3-TTS synthesis through llama.cpp and mtmd
- Jetpack Compose UI with Studio, Voices, History, and Settings screens
- Q4_K_M talker and Q8_0 audio model in app-private storage
- Voice profiles from microphone recordings or imported WAV files
- Playback, deletion, and WAV export for existing history
- CPU by default, with an optional Vulkan vocoder using the bundled Turnip driver on compatible Adreno devices
- Model reuse across generations, with a manual Unload control in Settings
- Complete WAV generation into a temporary preview, with manual Save WAV
- Qianwen launcher icon

## Model

The app uses this model package:

- `Qwen3-TTS-12Hz-0.6B-Base.Q4_K_M.gguf`
- `Qwen3-TTS-12Hz-0.6B-Base.mmproj-Q8_0.gguf`

Model files are downloaded from
[`mradermacher/Qwen3-TTS-12Hz-0.6B-Base-GGUF`](https://huggingface.co/mradermacher/Qwen3-TTS-12Hz-0.6B-Base-GGUF)
through hf-mirror into the app-private files directory. Together they use about 727 MiB.

## Requirements

- Windows, Git, JDK 17, and an x64 MinGW-w64 GCC/G++ toolchain to build the APK
- Android SDK Platform 36, Build Tools 36.0.0, CMake 3.22.1, and NDK r27d (27.3.13750724)
- An arm64 Android device, Android 12 / API 31 or newer

The native build targets `arm64-v8a`.

## Build

Run the following from the Android repository root in PowerShell. Set
`ANDROID_HOME` to the Android SDK, `JAVA_HOME` to JDK 17, and
`MINGW_BIN` to the directory containing the x64 MinGW-w64 `gcc.exe`
and `g++.exe`. Install the SDK components using Android Studio's SDK Manager
or `sdkmanager`.

```powershell
sdkmanager "platforms;android-36" "build-tools;36.0.0" "cmake;3.22.1" "ndk;27.3.13750724"

$deps = Join-Path (Split-Path -Parent (Get-Location).Path) 'qwen-android-deps'
New-Item -ItemType Directory -Path $deps -Force | Out-Null
git clone --branch b12138 --depth 1 https://github.com/ggml-org/llama.cpp.git "$deps/llama-vulkan-src"
$patch = (Resolve-Path 'app/src/main/cpp/llama-app/patches/llama.cpp-b12138.patch').Path
git -C "$deps/llama-vulkan-src" apply --check $patch
git -C "$deps/llama-vulkan-src" apply $patch

git clone https://github.com/KhronosGroup/Vulkan-Headers.git "$deps/vulkan-headers"
git -C "$deps/vulkan-headers" checkout --detach 88487f687f3100ebe1d3559a8a34d971ee85b977
git clone https://github.com/KhronosGroup/SPIRV-Headers.git "$deps/spirv-headers"
git -C "$deps/spirv-headers" checkout --detach 6efe5ae6b7c6ad29da3f2cc3f164b9c3d72b78b1
$cmake = "$env:ANDROID_HOME/cmake/3.22.1/bin/cmake.exe"
& $cmake -S "$deps/spirv-headers" -B "$deps/spirv-headers-build" -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$env:ANDROID_HOME/cmake/3.22.1/bin/ninja.exe" `
    "-DCMAKE_C_COMPILER=$env:MINGW_BIN/gcc.exe" "-DCMAKE_CXX_COMPILER=$env:MINGW_BIN/g++.exe" `
    -DSPIRV_HEADERS_ENABLE_TESTS=OFF `
    "-DCMAKE_INSTALL_PREFIX=$deps/spirv-headers-install"
& $cmake --install "$deps/spirv-headers-build"

$turnip = 'https://github.com/nihui/mesa-turnip-android-driver/releases/download/26.2.6/mesa-turnip-android-26.2.6.zip'
Invoke-WebRequest $turnip -OutFile "$deps/mesa-turnip-android-26.2.6.zip"
Expand-Archive "$deps/mesa-turnip-android-26.2.6.zip" -DestinationPath "$deps/turnip-26.2.6"
```

The patch is based on llama.cpp b12138
(`f7b41cca4fecc8164c7ea45bfb55dfff7633842f`).
The downloaded Turnip archive used for v0.1.1 has SHA-256
`936c31c5ed5947b3c2a1106043832db93d6af270b5dede86449b6a44fc40fff4`.
Build the patched native libraries and then assemble the APK.

```powershell
.\build-llama-app.ps1 -MingwBin $env:MINGW_BIN -DependenciesDir $deps
```

For an NDK installed outside the SDK, pass `-NdkDir` with its root.
The script produces the APK below.

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install On A Device

With a device connected through ADB:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On first launch, open Settings and download the model files. Select a
recorded or imported reference under Voices, then generate in Studio.
The Settings backend selector applies to the next load.
Select **Save WAV** in Studio to choose where to keep the current preview.
Generating again replaces the temporary preview.

## Repository Layout

```text
app/                         Android app, Compose UI, JNI wrapper
app/src/main/cpp/llama-app/  Native runtime build
app/src/main/java/.../data   Recorder and Room database
```

## License

This project is based on [Danmoreng/qwen3-tts-android](https://github.com/Danmoreng/qwen3-tts-android)
and retains its [MIT license](LICENSE). See [Third-party notices](THIRD_PARTY_NOTICES.md)
for the native runtime, headers, and driver source.
