$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$delivery=Join-Path $project '交付'
New-Item -ItemType Directory -Force -Path $delivery | Out-Null
$source=Join-Path $project 'app/build/outputs/apk/release/app-release.apk'
$target=Join-Path $delivery '漫画翻译助手-1.1.6.apk'
$buildTools='D:/claude-code-space/.toolchains/android/android-sdk/build-tools/36.0.0'
$env:JAVA_HOME=(Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk' -Directory | Select-Object -First 1).FullName
$temporary=Join-Path $env:TEMP ('manga116-'+[guid]::NewGuid()+'.apk')
try{
    Copy-Item -LiteralPath $source -Destination $temporary
    $badging=(& "$buildTools/aapt.exe" dump badging $temporary) -join "`n"
    if($LASTEXITCODE -ne 0){throw 'APK manifest read failed'}
    if($badging -notmatch "package: name='cn.local.manga' versionCode='45' versionName='1.1.6'"){throw 'Wrong package/version'}
    if($badging -match 'application-debuggable'){throw 'Debuggable delivery rejected'}
    if($badging -notmatch "native-code: 'arm64-v8a'\s*(?:`n|$)"){throw 'Unexpected native ABI'}
    if($badging -notmatch "sdkVersion:'29'" -or $badging -notmatch "targetSdkVersion:'35'"){throw 'Unexpected SDK'}
    $signature=(& "$buildTools/apksigner.bat" verify --print-certs $temporary) -join "`n"
    if($LASTEXITCODE -ne 0){throw 'APK signature verification failed'}
    $certificate='e61c186c4c5ea35320d6fa07fea40e26fe409cc5a0662aef752e318d8f27fe60'
    if($signature -notmatch [regex]::Escape($certificate)){throw 'Original signing certificate changed'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($temporary)
    try{$models=@($zip.Entries | Where-Object FullName -Like '*.onnx' | ForEach-Object FullName)}finally{$zip.Dispose()}
    if($models.Count -ne 1 -or $models[0] -ne 'assets/detector.onnx'){throw 'Unexpected bundled models'}
    Copy-Item -LiteralPath $source -Destination $target
    $hash=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    if($hash -ne (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'Delivery hash differs from verified APK'}
    "$hash *漫画翻译助手-1.1.6.apk" | Set-Content -Encoding utf8 (Join-Path $delivery '1.1.6-SHA256.txt')
    @{versionName='1.1.6';versionCode=45;package='cn.local.manga';sha256=$hash;bytes=(Get-Item -LiteralPath $target).Length;certificateSha256=$certificate;signatureVerified=$true;debuggable=$false;abi='arm64-v8a';minSdk=29;targetSdk=35;models=$models;deviceVerified=$false;verifiedUtc=(Get-Date).ToUniversalTime().ToString('o')} | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $delivery '1.1.6打包验证.json')
    $files=@{}
    Get-ChildItem (Join-Path $project 'app/src') -Recurse -File | ForEach-Object {$files[$_.FullName.Substring($project.Length+1).Replace('\','/')]=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
    $files['app/build.gradle']=(Get-FileHash -LiteralPath (Join-Path $project 'app/build.gradle')).Hash.ToLowerInvariant()
    $files | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $delivery '1.1.6源码SHA256.json')
    Get-Content -LiteralPath (Join-Path $delivery '1.1.6打包验证.json')
}finally{if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary}}
