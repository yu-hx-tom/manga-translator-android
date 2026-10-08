param(
    [Parameter(Mandatory=$true)][string]$Before,
    [Parameter(Mandatory=$true)][string]$After,
    [Parameter(Mandatory=$true)][string]$ReportDir
)
$ErrorActionPreference='Stop'
$toolchain=if($env:CODEX_ANDROID_TOOLCHAIN){$env:CODEX_ANDROID_TOOLCHAIN}else{'D:/claude-code-space/.toolchains/android'}
$jdk=Get-ChildItem (Join-Path $toolchain 'jdk') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'bin/javap.exe')} | Sort-Object Name -Descending | Select-Object -First 1
& py -3 (Join-Path $PSScriptRoot 'compare_classfiles.py') $Before $After $ReportDir --javap (Join-Path $jdk.FullName 'bin/javap.exe')
if($LASTEXITCODE -ne 0){throw '字节码比对失败，禁止认定格式化通过'}
