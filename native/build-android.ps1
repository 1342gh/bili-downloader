# 编译 C++ 核心为 libbili.so（arm64-v8a），产物直接放进 app/libs 供 build.ps1 打包。
#
# 用法：powershell -ExecutionPolicy Bypass -File native\build-android.ps1
$ErrorActionPreference = 'Stop'

$NDK  = 'D:\Android\sdk\ndk\30.0.16248370'
$CLANG = "$NDK\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
$STRIP = "$NDK\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
$LIBCXX = "$NDK\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so"

$SRC = $PSScriptRoot
$OUT = 'D:\android-build\app\libs\arm64-v8a'
New-Item -ItemType Directory -Force -Path $OUT | Out-Null

$files = @(
    "$SRC\src\md5.cpp",
    "$SRC\src\wbi.cpp",
    "$SRC\src\bili.cpp",
    "$SRC\src\download.cpp",
    "$SRC\src\ffmpeg_cmd.cpp",
    "$SRC\src\http_jni.cpp",
    "$SRC\src\jni_bridge.cpp"
)

Write-Output "[1/2] clang++ -> libbili.so"
& $CLANG --target=aarch64-linux-android29 -shared -fPIC -O2 -std=c++17 -Wall -Wextra `
    -I "$SRC\src" -I "$SRC\third_party" `
    @files -o "$OUT\libbili.so" -llog
if ($LASTEXITCODE -ne 0) { throw "clang++ failed: $LASTEXITCODE" }

Copy-Item $LIBCXX "$OUT\libc++_shared.so" -Force

Write-Output "[2/2] strip"
& $STRIP --strip-unneeded "$OUT\libbili.so"
& $STRIP --strip-unneeded "$OUT\libc++_shared.so"

Get-ChildItem $OUT -Filter *.so | ForEach-Object {
    Write-Output ("  {0}  {1:N0} KB" -f $_.Name, ($_.Length / 1KB))
}
