$ErrorActionPreference = 'Stop'
$readyRoot = $PSScriptRoot
$researchRoot = Split-Path $readyRoot
$sdkRoot = 'D:\Dev\_env\tools\android-sdk\api37-bt36-pt37.0.1\win-x64'
$buildTools = Join-Path $sdkRoot 'build-tools\36.0.0'
$jdkBin = 'D:\Dev\_env\tools\jdk\17.0.20+8\win-x64\bin'
$pythonExe = 'D:\Dev\_env\tools\python\3.12.13\win-x64\python.exe'
$androidJar = Join-Path $sdkRoot 'platforms\android-37.0\android.jar'
$outputRoot = Join-Path (Split-Path $researchRoot) 'apk'

function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed: $LASTEXITCODE" }
}

foreach ($variant in @('diagnostic', 'production')) {
    $variantRoot = Join-Path $readyRoot $variant
    $classesRoot = Join-Path $variantRoot 'classes'
    New-Item -ItemType Directory -Force $classesRoot, (Join-Path $variantRoot 'dex') | Out-Null
    $sourceRoot = if ($variant -eq 'production') { Join-Path $readyRoot 'src\com' } else { Join-Path $readyRoot 'src' }
    $sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' | ForEach-Object FullName)
    Run (Join-Path $jdkBin 'javac.exe') (@('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', $androidJar, '-d', $classesRoot) + $sourceFiles)
    Run (Join-Path $jdkBin 'jar.exe') @('cf', (Join-Path $variantRoot 'code.jar'), '-C', $classesRoot, '.')
    $minApi = if ($variant -eq 'production') { '21' } else { '26' }
    Run (Join-Path $jdkBin 'java.exe') @('-cp', (Join-Path $buildTools 'lib\d8.jar'), 'com.android.tools.r8.D8', '--min-api', $minApi, '--lib', $androidJar, '--output', (Join-Path $variantRoot 'dex'), (Join-Path $variantRoot 'code.jar'))
    Run (Join-Path $buildTools 'aapt2.exe') @('compile', '--dir', (Join-Path $variantRoot 'res'), '-o', (Join-Path $variantRoot 'res.zip'))
    Run (Join-Path $buildTools 'aapt2.exe') @('link', '-I', $androidJar, '--manifest', (Join-Path $variantRoot 'AndroidManifest.xml'), '-o', (Join-Path $variantRoot 'shell.apk'), (Join-Path $variantRoot 'res.zip'))
}
Run $pythonExe @((Join-Path $readyRoot 'package_apks.py'))
foreach ($variant in @('diagnostic', 'production')) {
    $variantRoot = Join-Path $readyRoot $variant
    $filename = if ($variant -eq 'production') { 'iOS-婷婷_倍速修复版_v1.4.apk' } else { '婷婷诊断_1.6.apk' }
    $output = Join-Path $outputRoot $filename
    Run (Join-Path $buildTools 'zipalign.exe') @('-P', '16', '-f', '4', (Join-Path $variantRoot 'unsigned.apk'), (Join-Path $variantRoot 'aligned.apk'))
    Run (Join-Path $jdkBin 'java.exe') @('-jar', (Join-Path $buildTools 'lib\apksigner.jar'), 'sign', '--ks', (Join-Path $researchRoot 'trial-signing.p12'), '--ks-pass', 'env:VOICE_STORE_PASSWORD', '--ks-key-alias', 'trial', '--out', $output, (Join-Path $variantRoot 'aligned.apk'))
    Run (Join-Path $jdkBin 'java.exe') @('-jar', (Join-Path $buildTools 'lib\apksigner.jar'), 'verify', '--verbose', '--print-certs', $output)
    Run (Join-Path $buildTools 'zipalign.exe') @('-c', '-P', '16', '4', $output)
    Copy-Item -LiteralPath $output -Destination (Join-Path $variantRoot 'final.apk') -Force
    Get-Item -LiteralPath $output | Select-Object Name, Length
}
