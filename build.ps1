$ErrorActionPreference = 'Stop'

# ==================== 环境 ====================
# 用环境变量覆盖，或者直接改下面的默认值。
# 注意：签名口令不要写进仓库，用 DSH_KS_PASS 传。
$JDK    = if ($env:DSH_JDK)    { $env:DSH_JDK }    else { 'D:\Java\jdk-21' }
$SDK    = if ($env:DSH_SDK)    { $env:DSH_SDK }    else { 'D:\Android\sdk' }
$ROOT   = if ($env:DSH_ROOT)   { $env:DSH_ROOT }   else { Split-Path $PSScriptRoot -Parent }
$BT     = "$SDK\build-tools\34.0.0"
$AJAR   = "$SDK\platforms\android-34\android.jar"
$APP    = "$ROOT\app"
$BUILD  = "$ROOT\out"
$KS     = if ($env:DSH_KEYSTORE) { $env:DSH_KEYSTORE } else { "$ROOT\dsh.keystore" }
$KSPASS = $env:DSH_KS_PASS
$KSALIAS= if ($env:DSH_KS_ALIAS) { $env:DSH_KS_ALIAS } else { 'dsh' }

if (-not $KSPASS) {
    throw '请先设置环境变量 DSH_KS_PASS（keystore 口令），不要把口令写进脚本'
}
if (-not (Test-Path $KS)) {
    throw "找不到 keystore：$KS（仓库里不包含签名私钥）"
}

$env:JAVA_HOME = $JDK
$env:Path = "$JDK\bin;$BT;$env:Path"

# ==================== clean ====================
if (Test-Path $BUILD) { Remove-Item $BUILD -Recurse -Force }
New-Item -ItemType Directory -Force -Path $BUILD | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\dexout" | Out-Null

# ==================== 1) aapt2 link (manifest -> apk shell) ====================
Write-Output '[1/5] aapt2 compile + link ...'
$resZip = "$BUILD\res.zip"
if (Test-Path "$APP\res") {
    & "$BT\aapt2.exe" compile --dir "$APP\res" -o $resZip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed: $LASTEXITCODE" }
}
& "$BT\aapt2.exe" link `
    -o "$BUILD\base.apk" `
    -I $AJAR `
    --manifest "$APP\AndroidManifest.xml" `
    $(if (Test-Path $resZip) { "-R"; "$resZip" }) `
    --java "$BUILD\gen" `
    --min-sdk-version 29 `
    --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed: $LASTEXITCODE" }

# ==================== 2) javac ====================
Write-Output '[2/5] javac ...'
$sources = Get-ChildItem "$APP\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$gens = Get-ChildItem "$BUILD\gen" -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
$all = @($sources) + @($gens)
# 第三方库（ExoPlayer/media3 等）：从 AAR 里抽出来的 classes.jar，
# 用 tools\fetch-libs.ps1 准备，二进制不进仓库。
$libJars = @(Get-ChildItem "$ROOT\libs\media3\jars" -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$cp = @($AJAR) + $libJars
& "$JDK\bin\javac.exe" -encoding UTF-8 --release 17 -classpath ($cp -join ';') -d "$BUILD\classes" @all
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

# ==================== 3) d8 -> classes.dex ====================
Write-Output '[3/5] d8 ...'
$classes = Get-ChildItem "$BUILD\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --lib $AJAR --min-api 29 --output "$BUILD\dexout" @classes @libJars
if ($LASTEXITCODE -ne 0) { throw "d8 failed: $LASTEXITCODE" }

# ==================== 4) put dex into apk ====================
Write-Output '[4/5] package ...'
Copy-Item "$BUILD\base.apk" "$BUILD\unsigned.apk" -Force
& "$JDK\bin\jar.exe" uf "$BUILD\unsigned.apk" -C "$BUILD\dexout" classes.dex
& "$JDK\bin\jar.exe" uf "$BUILD\unsigned.apk" -C "$APP" assets

# 原生库：ffmpeg / rife / C++ 核心，有就打包（这些二进制不进仓库）
$nativeLibs = @(
    @{ src = "$ROOT\ffmpeg\ffmpeg";                name = 'libffmpeg.so' },
    @{ src = "$ROOT\rife-jni\build\librifejni.so"; name = 'librifejni.so' }
)
foreach ($n in $nativeLibs) {
    if (Test-Path $n.src) {
        New-Item -ItemType Directory -Force -Path "$BUILD\native\lib\arm64-v8a" | Out-Null
        Copy-Item $n.src "$BUILD\native\lib\arm64-v8a\$($n.name)" -Force
        & "$JDK\bin\jar.exe" uf "$BUILD\unsigned.apk" -C "$BUILD\native" "lib/arm64-v8a/$($n.name)"
        Write-Output ('    bundled ' + $n.name + ': ' + [math]::Round((Get-Item $n.src).Length / 1MB, 1) + ' MB')
    }
}
$coreLibs = "$APP\libs\arm64-v8a"
if (Test-Path $coreLibs) {
    foreach ($so in Get-ChildItem $coreLibs -Filter *.so) {
        Copy-Item $so.FullName "$BUILD\native\lib\arm64-v8a\$($so.Name)" -Force
        & "$JDK\bin\jar.exe" uf "$BUILD\unsigned.apk" -C "$BUILD\native" "lib/arm64-v8a/$($so.Name)"
        Write-Output ('    bundled ' + $so.Name + ': ' + [math]::Round($so.Length / 1KB, 0) + ' KB')
    }
}
if ($LASTEXITCODE -ne 0) { throw "jar uf failed: $LASTEXITCODE" }

& "$BT\zipalign.exe" -f 4 "$BUILD\unsigned.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }

# ==================== 5) sign ====================
Write-Output '[5/5] sign ...'
& "$BT\apksigner.bat" sign --ks $KS --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" `
    --ks-key-alias $KSALIAS --out "$ROOT\BiliDownload.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed: $LASTEXITCODE" }

& "$BT\apksigner.bat" verify --print-certs "$ROOT\BiliDownload.apk" | Select-Object -First 4

Write-Output ''
Write-Output "[done] $ROOT\BiliDownload.apk"
Get-Item "$ROOT\BiliDownload.apk" | ForEach-Object { Write-Output ('  ' + [math]::Round($_.Length / 1KB, 1) + ' KB') }
