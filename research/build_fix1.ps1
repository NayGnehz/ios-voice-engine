$ErrorActionPreference = 'Stop'
$researchRoot = $PSScriptRoot
$sdkRoot = 'D:\Dev\_env\tools\android-sdk\api37-bt36-pt37.0.1\win-x64'
$buildTools = Join-Path $sdkRoot 'build-tools\36.0.0'
$jdkBin = 'D:\Dev\_env\tools\jdk\17.0.20+8\win-x64\bin'
$pythonExe = 'C:\Program Files\WindowsApps\OpenAI.CodexPrimaryRuntime.v26-1111-574-0_26.1111.574.0_x64__3k8sg7r9htsxt\dependencies\python\python.exe'
$androidJar = Join-Path $sdkRoot 'platforms\android-37.0\android.jar'
$diagnosticRoot = Join-Path $researchRoot 'diagnostic'

function Invoke-Checked([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with code $LASTEXITCODE" }
}
function Sign-Apk([string]$inputName, [string]$alignedName, [string]$outputName) {
    Invoke-Checked (Join-Path $buildTools 'zipalign.exe') @('-P', '16', '-f', '4', $inputName, $alignedName)
    Invoke-Checked (Join-Path $jdkBin 'java.exe') @('-jar', (Join-Path $buildTools 'lib\apksigner.jar'), 'sign', '--ks', (Join-Path $researchRoot 'trial-signing.p12'), '--ks-pass', 'env:VOICE_STORE_PASSWORD', '--ks-key-alias', 'trial', '--out', $outputName, $alignedName)
    Invoke-Checked (Join-Path $jdkBin 'java.exe') @('-jar', (Join-Path $buildTools 'lib\apksigner.jar'), 'verify', '--verbose', '--print-certs', $outputName)
    Invoke-Checked (Join-Path $buildTools 'zipalign.exe') @('-c', '-P', '16', '4', $outputName)
}

Invoke-Checked $pythonExe @((Join-Path $researchRoot 'build_direct_voice.py'))
Sign-Apk (Join-Path $researchRoot 'tingting-arm64-fix1-unsigned.apk') (Join-Path $researchRoot 'tingting-arm64-fix1-aligned.apk') (Join-Path $researchRoot 'ios-tingting-arm64-fix1.apk')

Invoke-Checked (Join-Path $jdkBin 'javac.exe') @('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', $androidJar, '-d', (Join-Path $diagnosticRoot 'classes'), (Join-Path $diagnosticRoot 'DiagnosticActivity.java'), (Join-Path $diagnosticRoot 'ExitTrace.java'))
Invoke-Checked (Join-Path $jdkBin 'jar.exe') @('cf', (Join-Path $diagnosticRoot 'probe.jar'), '-C', (Join-Path $diagnosticRoot 'classes'), '.')
Invoke-Checked (Join-Path $jdkBin 'java.exe') @('-cp', (Join-Path $buildTools 'lib\d8.jar'), 'com.android.tools.r8.D8', '--min-api', '26', '--lib', $androidJar, '--output', (Join-Path $diagnosticRoot 'dex'), (Join-Path $diagnosticRoot 'probe.jar'))
Invoke-Checked (Join-Path $buildTools 'aapt2.exe') @('compile', '--dir', (Join-Path $diagnosticRoot 'res'), '-o', (Join-Path $diagnosticRoot 'res.zip'))
Invoke-Checked (Join-Path $buildTools 'aapt2.exe') @('link', '-I', $androidJar, '--manifest', (Join-Path $diagnosticRoot 'AndroidManifest.xml'), '-o', (Join-Path $diagnosticRoot 'shell.apk'), (Join-Path $diagnosticRoot 'res.zip'))
Invoke-Checked $pythonExe @((Join-Path $diagnosticRoot 'package_probe.py'))
Sign-Apk (Join-Path $diagnosticRoot 'probe-unsigned.apk') (Join-Path $diagnosticRoot 'probe-aligned.apk') (Join-Path $researchRoot 'tingting-diagnostic-fix1.apk')
