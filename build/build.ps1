param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Sdk = $env:ANDROID_HOME
)
$ErrorActionPreference = "Stop"

# 仓库根目录（build\build.ps1 的上一级）
$root  = Split-Path -Parent $PSScriptRoot
$app   = Join-Path $root "app"
$build = Join-Path $root "build"

if (-not $Jdk) { throw "请用 -Jdk 指定 JDK 17 目录，或设置 JAVA_HOME" }
if (-not $Sdk) { throw "请用 -Sdk 指定 Android SDK 目录，或设置 ANDROID_HOME" }
$jdk = $Jdk
$bt  = Join-Path $Sdk "build-tools\34.0.0"
$androidJar = Join-Path $Sdk "platforms\android-34\android.jar"

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

New-Item -ItemType Directory -Force -Path "$build\out", "$build\classes", "$build\dex" | Out-Null
Get-ChildItem "$build\classes", "$build\dex" -Recurse -File | Remove-Item -Force -ErrorAction SilentlyContinue

Write-Output "== aapt2 compile =="
& "$bt\aapt2.exe" compile --dir "$app\res" -o "$build\out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Output "== aapt2 link =="
& "$bt\aapt2.exe" link -o "$build\out\base.apk" -I $androidJar --manifest "$app\AndroidManifest.xml" -A "$app\assets" --min-sdk-version 30 --target-sdk-version 34 --auto-add-overlay "$build\out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Output "== javac =="
$srcs = Get-ChildItem "$app\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d "$build\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Output "== d8 =="
$classes = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --release --min-api 30 --lib $androidJar --output "$build\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Output "== package dex =="
Copy-Item "$build\out\base.apk" "$build\out\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Resolve-Path "$build\out\unsigned.apk"), 'Update')
$entry = $zip.GetEntry("classes.dex")
if ($entry) { $entry.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Resolve-Path "$build\dex\classes.dex"), "classes.dex") | Out-Null
$zip.Dispose()

Write-Output "== zipalign =="
& "$bt\zipalign.exe" -f -p 4 "$build\out\unsigned.apk" "$build\out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Output "== keystore =="
$ks = Join-Path $build "release.jks"
if (-not (Test-Path $ks)) {
    & "$jdk\bin\keytool.exe" -genkeypair -v -keystore $ks -alias 1carvone -keyalg RSA -keysize 2048 -validity 10000 -storepass yqqs123456 -keypass yqqs123456 -dname "CN=1carvone, OU=1carvone, O=1carvone, L=CN, ST=CN, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Output "== apksigner =="
$ascii = Join-Path $build "out\signed.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias 1carvone --ks-pass "pass:yqqs123456" --key-pass "pass:yqqs123456" --v1-signing-enabled true --v2-signing-enabled true --out $ascii "$build\out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$bt\apksigner.bat" verify --print-certs $ascii
Get-Item $ascii | Select-Object FullName, Length