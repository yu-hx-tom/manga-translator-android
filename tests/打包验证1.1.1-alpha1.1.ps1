$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$proof=Join-Path $project '验证记录/1.1.1-alpha1.1-Lint修复'
$delivery=Join-Path $project '交付'
$apk=Join-Path $project 'app/build/outputs/apk/release/app-release.apk'
$old=Join-Path $delivery '漫画翻译助手-1.1.0-体验优化.apk'
$sdk='D:/claude-code-space/.toolchains/android/android-sdk/build-tools/36.0.0'
$java=(Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/java.exe' | Select-Object -First 1).FullName
$badging=& "$sdk/aapt2.exe" dump badging $apk 2>&1
if($LASTEXITCODE -ne 0){throw 'aapt2 failed'}
$badging | Set-Content (Join-Path $proof 'release-badging.txt') -Encoding utf8
$sign=& $java -jar "$sdk/lib/apksigner.jar" verify --print-certs $apk 2>&1
if($LASTEXITCODE -ne 0){throw 'APK signature verification failed'}
$sign | Set-Content (Join-Path $proof 'release-signature.txt') -Encoding utf8
$oldSign=& $java -jar "$sdk/lib/apksigner.jar" verify --print-certs $old 2>&1
if($LASTEXITCODE -ne 0){throw 'Baseline signature verification failed'}
$newCert=[regex]::Match(($sign -join "`n"),'certificate SHA-256 digest: ([0-9a-f]+)').Groups[1].Value
$oldCert=[regex]::Match(($oldSign -join "`n"),'certificate SHA-256 digest: ([0-9a-f]+)').Groups[1].Value
if($newCert -ne $oldCert -or $newCert -ne 'e61c186c4c5ea35320d6fa07fea40e26fe409cc5a0662aef752e318d8f27fe60'){throw 'Signer changed'}
$manifest=$badging -join "`n"
if($manifest -match 'application-debuggable'){throw 'Release is debuggable'}
if($manifest -notmatch "name='cn.local.manga' versionCode='38' versionName='1.1.1-alpha1.1'" -or $manifest -notmatch "sdkVersion:'29'" -or $manifest -notmatch "targetSdkVersion:'35'"){throw 'Package/version/SDK mismatch'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($apk)
try{
    $native=@($zip.Entries | Where-Object {$_.FullName -like 'lib/*/*.so'})
    $abis=@($native | ForEach-Object {$_.FullName.Split('/')[1]} | Sort-Object -Unique)
    if($abis.Count -ne 1 -or $abis[0] -ne 'arm64-v8a'){throw 'Unexpected native ABI'}
    $models=@($zip.Entries | Where-Object {$_.FullName -like '*.onnx'})
    if($models.Count -ne 1 -or $models[0].FullName -ne 'assets/detector.onnx'){throw 'Unexpected bundled model'}
    $stream=$models[0].Open();$sha=[Security.Cryptography.SHA256]::Create()
    try{$modelHash=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$sha.Dispose()}
    if($modelHash -ne (Get-FileHash "$project/app/src/main/assets/detector.onnx").Hash.ToLowerInvariant()){throw 'Model bytes changed'}
}finally{$zip.Dispose()}
$destination=Join-Path $delivery '漫画翻译助手-1.1.1-alpha1.1-Lint修复.apk'
Copy-Item -LiteralPath $apk -Destination $destination -Force
$hash=(Get-FileHash -LiteralPath $destination).Hash.ToLowerInvariant()
$hostChecks=Get-Content -LiteralPath (Join-Path $proof '主机检查/结果.json') -Raw | ConvertFrom-Json
if(!$hostChecks.passed){throw 'Host checks not passed'}
$mock=Get-Content -LiteralPath (Join-Path $proof '模拟接口检查.log') -Tail 1 | ConvertFrom-Json
$analyzer=Get-Content -LiteralPath (Join-Path $proof '日志分析器检查.log') -Tail 1 | ConvertFrom-Json
if(!$mock.passed -or !$analyzer.passed){throw 'Mock/analyzer checks not passed'}
foreach($variant in @('release','debug')) {
    [xml]$lint=Get-Content -LiteralPath (Join-Path $project "app/build/reports/lint-results-$variant.xml") -Raw
    if(@($lint.issues.issue).Where({$null -ne $_}).Count -ne 0){throw "Lint has unresolved findings: $variant"}
    foreach($ext in @('xml','html')){Copy-Item -LiteralPath (Join-Path $project "app/build/reports/lint-results-$variant.$ext") -Destination $proof -Force}
}
$result=[ordered]@{versionName='1.1.1-alpha1.1';versionCode=38;packageName='cn.local.manga';minSdk=29;targetSdk=35;bytes=(Get-Item $destination).Length;sha256=$hash;debuggable=$false;abis=$abis;certificateSha256=$newCert;matches110Certificate=$true;models=@(@{path='assets/detector.onnx';sha256=$modelHash});hostJavaAssertions=[int]$hostChecks.total;hostMockAssertions=$mock.checks;hostAnalyzerAssertions=$analyzer.checks;releaseBuild='passed offline';androidTestBuild='compiled only';lint='release/debug: 0 errors, 0 warnings; 18 prior findings locally reviewed/exempted';phoneAcceptance='真机未验收';performanceImprovement='未测量，不作结论';checkedAt=(Get-Date).ToUniversalTime().ToString('o')}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $delivery '1.1.1-alpha1.1打包验证.json') -Encoding utf8
"$hash  漫画翻译助手-1.1.1-alpha1.1-Lint修复.apk" | Set-Content -LiteralPath (Join-Path $delivery '1.1.1-alpha1.1-SHA256.txt') -Encoding utf8
$result | ConvertTo-Json -Depth 8
