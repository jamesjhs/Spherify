param(
    [string]$OpenCvSource = "",
    [string]$OutputDir = "$PSScriptRoot\..\third_party\opencv-android",
    [string]$Abi = "arm64-v8a"
)

$ErrorActionPreference = "Stop"

if (-not $OpenCvSource) {
    throw "Pass -OpenCvSource pointing to a local OpenCV source checkout."
}

$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$ndk = Join-Path $sdk "ndk\28.2.13676358"
$cmake = Join-Path $sdk "cmake\3.22.1\bin\cmake.exe"
$ninjaDir = Join-Path $sdk "cmake\3.22.1\bin"
$buildDir = Join-Path $OutputDir "build-$Abi"
$installDir = Join-Path $OutputDir "install-$Abi"

New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
New-Item -ItemType Directory -Path $installDir -Force | Out-Null
$env:PATH = "$ninjaDir;$env:PATH"

$configureArgs = @(
    "-S", $OpenCvSource,
    "-B", $buildDir,
    "-G", "Ninja",
    "-DCMAKE_MAKE_PROGRAM=$ninjaDir\ninja.exe",
    "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake",
    "-DANDROID_ABI=$Abi",
    "-DANDROID_PLATFORM=android-26",
    "-DANDROID_STL=c++_shared",
    "-DCMAKE_BUILD_TYPE=Release",
    "-DCMAKE_INSTALL_PREFIX=$installDir",
    "-DBUILD_SHARED_LIBS=ON",
    "-DBUILD_opencv_java=OFF",
    "-DBUILD_opencv_stitching=ON",
    "-DBUILD_opencv_features2d=ON",
    "-DBUILD_opencv_calib3d=ON",
    "-DBUILD_opencv_imgcodecs=ON",
    "-DBUILD_opencv_imgproc=ON",
    "-DBUILD_opencv_photo=ON",
    "-DBUILD_opencv_flann=ON",
    "-DBUILD_TESTS=OFF",
    "-DBUILD_PERF_TESTS=OFF",
    "-DBUILD_EXAMPLES=OFF",
    "-DBUILD_ANDROID_EXAMPLES=OFF"
)
& $cmake @configureArgs
if ($LASTEXITCODE -ne 0) {
    throw "OpenCV CMake configure failed with exit code $LASTEXITCODE."
}

& $cmake --build $buildDir --target install --config Release
if ($LASTEXITCODE -ne 0) {
    throw "OpenCV native build failed with exit code $LASTEXITCODE."
}

$config = Get-ChildItem -Path $installDir -Recurse -Filter OpenCVConfig.cmake | Select-Object -First 1
if (-not $config) {
    throw "OpenCV build completed, but OpenCVConfig.cmake was not found below $installDir."
}
$opencvDir = Split-Path -Parent $config.FullName

Write-Host "OpenCV native install complete:"
Write-Host $installDir
Write-Host ""
Write-Host "Add this to local.properties:"
Write-Host "spherify.nativeStitcher=true"
Write-Host "spherify.opencvNativeDir=$opencvDir"
