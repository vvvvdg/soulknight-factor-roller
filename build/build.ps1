#     #     #
#   构建脚本（Windows / PowerShell 5.1+）
#
#   手工调用 Android SDK 工具链打包，不依赖 Gradle。
#   用法：
#     powershell -ExecutionPolicy Bypass -File build\build.ps1 -Jdk "C:\jdk-17" -Sdk "C:\android-sdk"
#   或者预先设置环境变量 JAVA_HOME / ANDROID_HOME 后直接运行。
#
param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"

$root  = Split-Path $PSScriptRoot -Parent
$app   = Join-Path $root "app"
$build = Join-Path $root "build\out"

if (-not $Jdk) { throw "缺少 JDK：用 -Jdk 指定，或设置 JAVA_HOME" }
if (-not $Sdk) { throw "缺少 Android SDK：用 -Sdk 指定，或设置 ANDROID_HOME" }

$bt         = Join-Path $Sdk "build-tools\34.0.0"
$androidJar = Join-Path $Sdk "platforms\android-34\android.jar"
$javaHome   = if (Test-Path (Join-Path $Jdk "bin\javac.exe")) { $Jdk } else { Join-Path $Jdk "jdk" }

foreach ($p in @($bt, $androidJar, (Join-Path $javaHome "bin\javac.exe"))) {
    if (-not (Test-Path $p)) { throw "找不到：$p" }
}

$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

New-Item -ItemType Directory -Force -Path $build,
    (Join-Path $build "classes"),
    (Join-Path $build "dex") | Out-Null
Get-ChildItem (Join-Path $build "classes"), (Join-Path $build "dex") -Recurse -File -ErrorAction SilentlyContinue |
    Remove-Item -Force

Write-Output "== aapt2 compile =="
& "$bt\aapt2.exe" compile --dir "$app\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Output "== aapt2 link =="
& "$bt\aapt2.exe" link -o "$build\base.apk" -I $androidJar `
    --manifest "$app\AndroidManifest.xml" -A "$app\assets" `
    --min-sdk-version 30 --target-sdk-version 34 --auto-add-overlay "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Output "== javac =="
$srcs = (Get-ChildItem "$app\src" -Recurse -Filter *.java).FullName
& "$javaHome\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn `
    -classpath $androidJar -d "$build\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Output "== d8 =="
$classes = (Get-ChildItem "$build\classes" -Recurse -Filter *.class).FullName
& "$bt\d8.bat" --release --min-api 30 --lib $androidJar --output "$build\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Output "== package dex =="
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item "$build\base.apk" "$build\withdex.apk" -Force
$zip = [System.IO.Compression.ZipFile]::Open("$build\withdex.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, "$build\dex\classes.dex", "classes.dex", 'Optimal') | Out-Null
$zip.Dispose()

Write-Output "== zipalign =="
& "$bt\zipalign.exe" -f -p 4 "$build\withdex.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Output "== keystore =="
$ks = Join-Path $root "build\release.jks"
if (-not (Test-Path $ks)) {
    & "$javaHome\bin\keytool.exe" -genkeypair -v -keystore $ks -alias yqqs `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass yqqs123456 -keypass yqqs123456 `
        -dname "CN=yqqs, OU=dev, O=yqqs, L=CN, ST=CN, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Output "== apksigner =="
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias yqqs `
    --ks-pass "pass:yqqs123456" --key-pass "pass:yqqs123456" `
    --v1-signing-enabled true --v2-signing-enabled true `
    --out "$build\signed.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$bt\apksigner.bat" verify --print-certs "$build\signed.apk"

if ($Out) {
    Copy-Item "$build\signed.apk" $Out -Force
    Get-Item $Out | Select-Object Name, Length
} else {
    Get-Item "$build\signed.apk" | Select-Object Name, Length
}