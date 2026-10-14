<# .SYNOPSIS Android ABI별 RIFE JNI를 빌드해 APK에 필요한 라이브러리만 준비합니다. #>
param([string[]]$Abis = @('arm64-v8a', 'x86_64', 'armeabi-v7a', 'x86'))
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/Prepare.ps1"
. "$PSScriptRoot/../../Environment.ps1"
$cmake = "$env:ANDROID_HOME/cmake/3.22.1/bin/cmake.exe"
$ninja = "$env:ANDROID_HOME/cmake/3.22.1/bin/ninja.exe"
$ndk = "$env:ANDROID_HOME/ndk/29.0.13846066"
if (-not (Test-Path -LiteralPath "$cmake") -or -not (Test-Path -LiteralPath "$ndk/build/cmake/android.toolchain.cmake")) {
    & "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" "--sdk_root=$env:ANDROID_HOME" --install 'ndk;29.0.13846066' 'cmake;3.22.1' 2>&1 | Select-Object -Last 30
    if ($LASTEXITCODE) { throw 'NDK·CMake 설치에 실패했습니다.' }
}
$cache = "$ProjectRoot/.build/cache/rife"
foreach ($abi in $Abis) {
    if ($abi -notin @('arm64-v8a', 'x86_64', 'armeabi-v7a', 'x86')) { throw '지원하지 않는 ABI입니다.' }
    $build = "$cache/build-$abi"
    $ncnn = "$cache/ncnn/ncnn-20260526-android-vulkan/$abi/lib/cmake/ncnn"
    & $cmake -S $PSScriptRoot -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" "-DANDROID_ABI=$abi" '-DANDROID_PLATFORM=android-24' '-DANDROID_STL=c++_static' '-DCMAKE_BUILD_TYPE=MinSizeRel' "-Dncnn_DIR=$ncnn" "-DRIFE_SOURCE=$cache/upstream/src" 2>&1 | Select-Object -Last 30
    if ($LASTEXITCODE) { throw 'RIFE 구성에 실패했습니다.' }
    & $cmake --build $build --parallel 2 2>&1 | Select-Object -Last 30
    if ($LASTEXITCODE) { throw 'RIFE 컴파일에 실패했습니다.' }
    $target = "$ProjectRoot/.build/runtime/rife/jniLibs/$abi"
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Copy-Item -LiteralPath "$build/libseal_rife.so" -Destination "$target/libseal_rife.so"
    & "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" --strip-unneeded "$target/libseal_rife.so"
    if ($LASTEXITCODE) { throw 'RIFE 배포 라이브러리 준비에 실패했습니다.' }
    Get-Item -LiteralPath "$target/libseal_rife.so" | Select-Object FullName, Length
}
