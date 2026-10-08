param([string]$ReportDir='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$report=if($ReportDir){$ReportDir}else{Join-Path $project '验证记录/1.1.1-alpha1/主机检查'}
$out=Join-Path $report 'classes'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$toolchain='D:/claude-code-space/.toolchains/android'
$compiler=Get-ChildItem "$toolchain/jdk/*/bin/javac.exe" | Select-Object -First 1
$java=Join-Path $compiler.DirectoryName 'java.exe'
$android=Join-Path $toolchain 'android-sdk/platforms/android-36/android.jar'
$json=Join-Path $PSScriptRoot 'deps/json-20250517.jar'
$production=Join-Path $project 'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
if(!(Test-Path -LiteralPath "$production/cn/local/manga/PerformanceDiagnostics.class")){throw 'Build release first'}
if((Get-FileHash -LiteralPath $json).Hash -ne '3EA61B2A06E31EDF1C91134FE9106B0EBB16628BE169F3DB75BC7A2B06B45796'){throw 'JSON dependency checksum mismatch'}
$fixtures=@("$PSScriptRoot/api-host/android/os/SystemClock.java","$PSScriptRoot/0.9.4验证/请求与设置/host/android/content/Context.java","$PSScriptRoot/0.9.4验证/请求与设置/host/android/graphics/Bitmap.java","$PSScriptRoot/host-geometry/android/graphics/Rect.java","$PSScriptRoot/v090-cleanup-host/android/graphics/BitmapFactory.java","$PSScriptRoot/v073-cache-host/android/util/Base64.java","$PSScriptRoot/v060-host/cn/local/manga/Detector.java","$PSScriptRoot/rtdetr-session-host/cn/local/manga/RtDetrDetector.java")
$tests=@('PartialRetryChecks','LocalOverlayChecks','CropTextLayoutChecks','TargetedBubbleChecks','CleanupPlanChecks','RenderedPageCacheChecks','PerformanceDiagnosticsHostChecks')
$fixtures+=@(Get-ChildItem (Join-Path $PSScriptRoot 'storage-host/android/system') -Filter '*.java' | ForEach-Object FullName)
$sources=$tests|ForEach-Object {Join-Path $PSScriptRoot ($_+'.java')}
& $compiler.FullName '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -cp "$production;$json;$android" -d $out $fixtures $sources
if($LASTEXITCODE -ne 0){throw 'Host fixture compilation failed'}
$counts=@{}
foreach($test in $tests){
    $fixture=Join-Path $report ($test+'-'+[guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Force -Path $fixture | Out-Null
    $lines=& $java '-Dfile.encoding=UTF-8' -cp "$out;$production;$json;$android" "cn.local.manga.$test" $fixture 2>&1
    $code=$LASTEXITCODE
    $lines|Set-Content -LiteralPath (Join-Path $report ($test+'.log')) -Encoding utf8
    $lines|Write-Output
    if($code -ne 0){throw "$test failed"}
    if(($lines -join "`n") -match '(\d+) (?:checks|assertions) passed'){$counts[$test]=[int]$Matches[1]}else{throw "Missing count: $test"}
}
@{passed=$true;checks=$counts;total=($counts.Values|Measure-Object -Sum).Sum;evidence='Host synthetic fixtures with actual release classes; Android UI/Bitmap/ONNX runtime NOT verified'} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $report '结果.json') -Encoding utf8
