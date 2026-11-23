# 准备第三方库（media3 / guava），把 AAR 里的 classes.jar 抽到 libs\media3\jars。
# 这些二进制不进仓库，构建前跑一次即可（需要联网）。
$ErrorActionPreference = 'Stop'

$ROOT = if ($env:DSH_ROOT) { $env:DSH_ROOT } else { Split-Path $PSScriptRoot -Parent }
$DIR  = Join-Path $ROOT 'libs\media3'
$JARS = Join-Path $DIR 'jars'
New-Item -ItemType Directory -Force $DIR, $JARS | Out-Null

$GOOGLE = 'https://maven.aliyun.com/repository/google'
$PUBLIC = 'https://maven.aliyun.com/repository/public'
$VER = '1.4.1'

# media3 模块（aar）
$media3 = @('exoplayer', 'common', 'container', 'datasource', 'decoder', 'extractor', 'database')
foreach ($m in $media3) {
    $name = "media3-$m-$VER"
    $aar = Join-Path $DIR "$name.aar"
    if (-not (Test-Path $aar)) {
        Write-Output "download $name.aar"
        & curl.exe -sL --ssl-no-revoke -o $aar "$GOOGLE/androidx/media3/media3-$m/$VER/$name.aar"
    }
}

# androidx 依赖（aar）
$androidxAars = @(
    @{ path = 'androidx/core/core/1.8.0/core-1.8.0.aar';            file = 'core-1.8.0.aar' },
    @{ path = 'androidx/exifinterface/exifinterface/1.3.6/exifinterface-1.3.6.aar'; file = 'exifinterface-1.3.6.aar' }
)
foreach ($a in $androidxAars) {
    $out = Join-Path $DIR $a.file
    if (-not (Test-Path $out)) {
        Write-Output "download $($a.file)"
        & curl.exe -sL --ssl-no-revoke -o $out "$GOOGLE/$($a.path)"
    }
}

# 普通 jar
$jarsToGet = @(
    @{ path = 'com/google/guava/guava/33.0.0-android/guava-33.0.0-android.jar'; file = 'guava-33.0.0-android.jar' },
    @{ path = 'com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar';   file = 'failureaccess-1.0.2.jar' },
    @{ path = 'androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar';      file = 'annotation-1.3.0.jar' },
    @{ path = 'androidx/collection/collection/1.2.0/collection-1.2.0.jar';      file = 'collection-1.2.0.jar' }
)
foreach ($j in $jarsToGet) {
    $out = Join-Path $DIR $j.file
    if (-not (Test-Path $out)) {
        Write-Output "download $($j.file)"
        if ($j.file -like 'guava*' -or $j.file -like 'failureaccess*') {
            & curl.exe -sL --ssl-no-revoke -o $out "$PUBLIC/$($j.path)"
        } else {
            & curl.exe -sL --ssl-no-revoke -o $out "$GOOGLE/$($j.path)"
        }
    }
    Copy-Item $out $JARS -Force
}

# 从 AAR 里抽 classes.jar
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($aar in Get-ChildItem $DIR -Filter *.aar) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($aar.FullName)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'classes.jar' } | Select-Object -First 1
        if ($entry) {
            $target = Join-Path $JARS ($aar.BaseName + '.jar')
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            Write-Output "extract $($aar.BaseName).jar"
        }
    } finally {
        $zip.Dispose()
    }
}

Write-Output ''
Write-Output "完成：$JARS"
Get-ChildItem $JARS -Filter *.jar | Measure-Object -Property Length -Sum |
    ForEach-Object { Write-Output ('  ' + $_.Count + ' 个 jar，' + [math]::Round($_.Sum / 1MB, 1) + ' MB') }
