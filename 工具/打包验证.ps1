param([Parameter(Mandatory=$true)][string]$BuildRecord)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$record=Get-Content -LiteralPath $BuildRecord -Raw -Encoding UTF8 | ConvertFrom-Json
if($record.status -ne 'success'){throw '只接受成功构建记录'}
$apk=Join-Path $project 'app/build/outputs/apk/release/app-release.apk'
if((Get-FileHash -LiteralPath $apk).Hash.ToLowerInvariant() -ne $record.apkSha256){throw 'APK 已改变，与构建记录不一致'}
$toolchain=if($env:CODEX_ANDROID_TOOLCHAIN){$env:CODEX_ANDROID_TOOLCHAIN}else{'D:/claude-code-space/.toolchains/android'}
$tools=Join-Path $toolchain 'android-sdk/build-tools/36.0.0'
$jdk=Get-ChildItem (Join-Path $toolchain 'jdk') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'bin/java.exe')} | Sort-Object Name -Descending | Select-Object -First 1
$env:JAVA_HOME=$jdk.FullName
$temp=Join-Path $env:TEMP ('manga-verify-'+[guid]::NewGuid()+'.apk')
try {
    Copy-Item -LiteralPath $apk -Destination $temp
    $badging=& (Join-Path $tools 'aapt.exe') dump badging $temp
    if($LASTEXITCODE -ne 0){throw 'APK 元数据读取失败'}
    $line=($badging | Select-Object -First 1)
    if($line -notmatch "package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'"){throw 'APK 版本信息缺失'}
    $package=$Matches[1];$versionCode=[int]$Matches[2];$versionName=$Matches[3]
    if($package -ne 'cn.local.manga' -or $versionCode -ne $record.versionCode -or $versionName -ne $record.versionName){throw 'APK 版本与构建记录不一致'}
    if(($badging -join "`n") -match 'application-debuggable'){throw '正式 APK 不能为 debuggable'}
    $signature=& (Join-Path $tools 'apksigner.bat') verify --print-certs $temp
    if($LASTEXITCODE -ne 0){throw 'APK 签名验证失败'}
    $cert=($signature | Where-Object {$_ -match 'Signer #1 certificate SHA-256 digest:'}) -replace '^.*digest: *',''
    if($cert -ne 'e61c186c4c5ea35320d6fa07fea40e26fe409cc5a0662aef752e318d8f27fe60'){throw '签名证书与已有版本不同'}
    $result=[ordered]@{versionName=$versionName;versionCode=$versionCode;package=$package;sha256=$record.apkSha256;bytes=(Get-Item -LiteralPath $apk).Length;certificateSha256=$cert;signatureVerified=$true;commit=$record.commit;dirty=$record.dirty;allowDirty=$record.allowDirty;deliverable=$record.deliverable;buildRecord=(Resolve-Path -LiteralPath $BuildRecord).Path}
    $output=Join-Path (Split-Path $BuildRecord -Parent) '打包验证.json'
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $output -Encoding utf8
    Write-Host "打包来源验证通过：$versionName / $versionCode / $($record.commit)，可交付=$($record.deliverable)"
} finally {if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}}
