$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$proof=Join-Path $project '验证记录/1.1.2存储改造'
$delivery=Join-Path $project '交付'
$apk=Join-Path $project 'app/build/outputs/apk/release/app-release.apk'
$sdk='D:/claude-code-space/.toolchains/android/android-sdk/build-tools/36.0.0'
$java=(Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/java.exe' | Select-Object -First 1).FullName
$storage=Get-Content (Join-Path $proof '主机存储检查/结果.json') -Raw | ConvertFrom-Json
$algorithms=Get-Content (Join-Path $proof '算法与请求回归/结果.json') -Raw | ConvertFrom-Json
$other=Get-Content (Join-Path $proof '配套回归/结果.json') -Raw | ConvertFrom-Json
if(!$storage.passed -or !$algorithms.passed -or !$other.passed){throw 'Regression evidence incomplete'}
$latest=(Get-ChildItem (Join-Path $project 'app/src/main') -Recurse -File | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).LastWriteTimeUtc
if((Get-Item $apk).LastWriteTimeUtc -lt $latest){throw 'APK predates production source'}
$badging=& "$sdk/aapt2.exe" dump badging $apk 2>&1
if($LASTEXITCODE -ne 0){throw 'aapt2 failed'}
$badging | Set-Content (Join-Path $proof 'release-badging.txt') -Encoding utf8
$sign=& $java -jar "$sdk/lib/apksigner.jar" verify --print-certs $apk 2>&1
if($LASTEXITCODE -ne 0){throw 'APK signature verification failed'}
$sign | Set-Content (Join-Path $proof 'release-signature.txt') -Encoding utf8
$cert=[regex]::Match(($sign -join "`n"),'certificate SHA-256 digest: ([0-9a-f]+)').Groups[1].Value
if($cert -ne 'e61c186c4c5ea35320d6fa07fea40e26fe409cc5a0662aef752e318d8f27fe60'){throw 'Signer changed'}
$manifest=$badging -join "`n"
if($manifest -match 'application-debuggable'){throw 'Release is debuggable'}
if($manifest -notmatch "name='cn.local.manga' versionCode='41' versionName='1.1.2'" -or $manifest -notmatch "sdkVersion:'29'" -or $manifest -notmatch "targetSdkVersion:'35'"){throw 'Package/version/SDK mismatch'}
function StreamHash($stream){$sha=[Security.Cryptography.SHA256]::Create();try{([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose();$stream.Dispose()}}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($apk)
try{
    $abis=@($zip.Entries | Where-Object {$_.FullName -like 'lib/*/*.so'} | ForEach-Object {$_.FullName.Split('/')[1]} | Sort-Object -Unique)
    if($abis.Count -ne 1 -or $abis[0] -ne 'arm64-v8a'){throw 'Unexpected native ABI'}
    $models=@($zip.Entries | Where-Object {$_.FullName -like '*.onnx'})
    if($models.Count -ne 1 -or $models[0].FullName -ne 'assets/detector.onnx'){throw 'Unexpected bundled model'}
    $modelHash=StreamHash $models[0].Open()
    if($modelHash -ne 'd73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e'){throw 'Detector model changed'}
}finally{$zip.Dispose()}
$baseline=[IO.Compression.ZipFile]::OpenRead((Join-Path $proof '开工前源码.zip'))
$unchanged=[ordered]@{}
try{
    foreach($name in @('WhiteBubbleCleaner','GrayGlyphRepair','RepairPixels','BubbleLayout','NearbyTextLayout','Typesetter','TextRenderer','PageComposer','TextStyle','ImageCleanup','Region','Detector','DetectorModels','RtDetrDetector','RtDetrRegions','ApiClient','AppSettings','ApiPresets','CleanupPlan','TranslationTranscript','LocalComics')){
        $entry=$baseline.GetEntry("src/main/java/cn/local/manga/$name.java")
        if($null -eq $entry){throw "Baseline source missing: $name"}
        $old=StreamHash $entry.Open()
        $current=(Get-FileHash (Join-Path $project "app/src/main/java/cn/local/manga/$name.java")).Hash.ToLowerInvariant()
        if($old -ne $current){throw "Frozen algorithm/settings source changed: $name"}
        $unchanged[$name]=$current
    }
}finally{$baseline.Dispose()}
$unchanged | ConvertTo-Json | Set-Content (Join-Path $proof '未改动算法与设置哈希.json') -Encoding utf8
$javap=Join-Path (Split-Path $java) 'javap.exe'
$bytecode=& $javap -p -c (Join-Path $project 'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes/cn/local/manga/ShellActivity.class')
if($LASTEXITCODE -ne 0){throw 'javap failed'}
$listing=$bytecode -join "`n"
if($listing -match 'ImageView.setAlpha:\(I\)V' -or [regex]::Matches($listing,'ImageView.setAlpha:\(F\)V').Count -ne 3){throw 'Navigation alpha dispatch regression'}
$bytecode | Set-Content (Join-Path $proof '导航图标字节码.txt') -Encoding utf8
foreach($variant in @('release','debug')){
    [xml]$lint=Get-Content (Join-Path $project "app/build/reports/lint-results-$variant.xml") -Raw
    if(@($lint.issues.issue).Where({$null -ne $_}).Count -ne 0){throw "Lint findings: $variant"}
    foreach($ext in @('xml','html')){Copy-Item (Join-Path $project "app/build/reports/lint-results-$variant.$ext") $proof -Force}
}
$name='漫画翻译助手-1.1.2-存储改造.apk'
$destination=Join-Path $delivery $name
Copy-Item -LiteralPath $apk -Destination $destination -Force
$hash=(Get-FileHash $destination).Hash.ToLowerInvariant()
$result=[ordered]@{versionName='1.1.2';versionCode=41;packageName='cn.local.manga';minSdk=29;targetSdk=35;bytes=(Get-Item $destination).Length;sha256=$hash;debuggable=$false;abis=$abis;certificateSha256=$cert;unchangedCertificate=$true;modelSha256=$modelHash;unchangedAlgorithmAndSettingsFiles=$unchanged.Count;storageHostChecks=$storage.checks;algorithmAndRequestHostChecks=$algorithms.total;additionalRegressions=$other;navigationAlphaDispatch='3 float calls; no integer calls';lint='release/debug: 0 errors, 0 warnings';androidTest='compiled only; not run';phoneAcceptance='pending user manual verification';pixelGoldenComparison='not available; no verified 1.1.1 final phone golden';phoneStorageSavings='not measured';checkedAt=(Get-Date).ToUniversalTime().ToString('o')}
$result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $delivery '1.1.2打包验证.json') -Encoding utf8
"$hash  $name" | Set-Content (Join-Path $delivery '1.1.2-SHA256.txt') -Encoding utf8
$result | ConvertTo-Json -Depth 8
