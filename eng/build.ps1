[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OriginalApk,
    [Parameter(Mandatory)][string]$RuntimeApk,
    [Parameter(Mandatory)][string]$Keystore,
    [string]$Alias = 'trial'
)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path $PSScriptRoot
$ancestor = Get-Item -LiteralPath $sourceRoot
while ($ancestor -and !(Test-Path -LiteralPath (Join-Path $ancestor.FullName '_env\bin\Invoke-Portable.ps1'))) {
    $ancestor = $ancestor.Parent
}
if (!$ancestor) { throw 'Restore the shared portable _env in an ancestor directory first; no global tool fallback.' }
$entry = Join-Path $ancestor.FullName '_env\bin\Invoke-Portable.ps1'
$python = & $entry -Action Tool -Tool python
$java = & $entry -Action Tool -Tool java
$androidJar = & $entry -Action Tool -Tool android-jar
$jdkRoot = Split-Path (Split-Path $java)
$sdkRoot = Split-Path (Split-Path (Split-Path $androidJar))
$instance = Join-Path $ancestor.FullName '_cache\projects\ios-voice-engine\release'
New-Item -ItemType Directory -Force -Path (Join-Path $instance 'temp') | Out-Null
$previous = @{}
try {
    foreach ($name in @('TEMP', 'TMP', 'PYTHONDONTWRITEBYTECODE', 'PYTHONNOUSERSITE')) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $env:TEMP = Join-Path $instance 'temp'; $env:TMP = $env:TEMP
    $env:PYTHONDONTWRITEBYTECODE = '1'; $env:PYTHONNOUSERSITE = '1'
    & $python (Join-Path $sourceRoot 'tools\test.py') --jdk $jdkRoot --work (Join-Path $instance 'tests')
    if ($LASTEXITCODE -ne 0) { throw 'Host regressions failed' }
    & $python (Join-Path $sourceRoot 'tools\build.py') --original-apk $OriginalApk --runtime-apk $RuntimeApk `
        --jdk $jdkRoot --sdk $sdkRoot --work (Join-Path $instance 'build') --output (Join-Path $instance 'dist') `
        --keystore $Keystore --alias $Alias
    if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
