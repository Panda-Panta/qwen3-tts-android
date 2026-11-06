param(
    [string]$SdkDir = $env:ANDROID_HOME,
    [Parameter(Mandatory)][string]$MingwBin,
    [string]$DependenciesDir = "$PSScriptRoot/..",
    [string]$NdkDir,
    [string]$Gradle = "$PSScriptRoot/gradlew.bat",
    [int]$Jobs = 10
)

$ErrorActionPreference = "Stop"
if (-not $SdkDir) {
    throw "Set ANDROID_HOME or pass -SdkDir."
}

$workspace = (Resolve-Path -LiteralPath $DependenciesDir).Path.Replace('\', '/')
$appDir = $PSScriptRoot.Replace('\', '/')
$SdkDir = (Resolve-Path -LiteralPath $SdkDir).Path.Replace('\', '/')
$env:ANDROID_HOME = $SdkDir
$MingwBin = (Resolve-Path -LiteralPath $MingwBin).Path.Replace('\', '/')
if (-not $NdkDir) {
    $NdkDir = "$SdkDir/ndk/27.3.13750724"
    if (-not (Test-Path -LiteralPath $NdkDir)) {
        $NdkDir = "$workspace/ndk/android-ndk-r27d"
    }
}
$ndk = (Resolve-Path -LiteralPath $NdkDir).Path.Replace('\', '/')
$llama = "$workspace/llama-vulkan-src"
$patch = "$appDir/app/src/main/cpp/llama-app/patches/llama.cpp-b12138.patch"
$llamaCommit = git -C $llama rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $llamaCommit -ne 'f7b41cca4fecc8164c7ea45bfb55dfff7633842f') {
    throw "Use llama.cpp b12138 in $llama. See README.md."
}
git -C $llama apply --reverse --check $patch
if ($LASTEXITCODE -ne 0) {
    throw "Apply the bundled llama.cpp patch before building. See README.md."
}

$toolchain = "$ndk/toolchains/llvm/prebuilt/windows-x86_64"
$cmakeBin = "$SdkDir/cmake/3.22.1/bin"
$nativeBuild = "$appDir/build/llamaNative"
$jniDir = "$appDir/build/llamaAppRuntime/arm64-v8a"
$env:Path = "$MingwBin;$env:Path"

& "$cmakeBin/cmake.exe" -S "$appDir/app/src/main/cpp/llama-app" -B $nativeBuild -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$cmakeBin/ninja.exe" "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" `
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_shared `
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release `
    "-DLLAMA_SOURCE_DIR=$llama" "-DAPP_JNI_DIR=$jniDir" `
    "-DVulkan_INCLUDE_DIR=$workspace/vulkan-headers/include" `
    "-DVulkan_LIBRARY=$toolchain/sysroot/usr/lib/aarch64-linux-android/31/libvulkan.so" `
    "-DVulkan_GLSLC_EXECUTABLE=$ndk/shader-tools/windows-x86_64/glslc.exe" `
    "-DSPIRV-Headers_DIR=$workspace/spirv-headers-install/share/cmake/SPIRV-Headers" `
    "-DHOST_C_COMPILER=$MingwBin/gcc.exe" "-DHOST_CXX_COMPILER=$MingwBin/g++.exe" `
    -DQWEN_APP_RUNTIME_ONLY=OFF
if ($LASTEXITCODE) { exit $LASTEXITCODE }

& "$cmakeBin/cmake.exe" --build $nativeBuild --target qwen_llama_tts_jni -j $Jobs
if ($LASTEXITCODE) { exit $LASTEXITCODE }

Copy-Item -LiteralPath "$workspace/turnip-26.2.6/libvulkan_freedreno.so",
    "$toolchain/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" -Destination $jniDir

& $Gradle -p $appDir --console=plain '-Pkotlin.incremental=false' :app:assembleDebug
exit $LASTEXITCODE
