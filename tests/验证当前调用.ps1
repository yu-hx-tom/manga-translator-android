param([string]$ReportDir='')
$ErrorActionPreference='Stop'
$testsRoot=$PSScriptRoot
$project=Split-Path $testsRoot -Parent
$toolchain=if($env:CODEX_ANDROID_TOOLCHAIN){$env:CODEX_ANDROID_TOOLCHAIN}else{'D:\claude-code-space\.toolchains\android'}
$compiler=Get-ChildItem "$toolchain\jdk\*\bin\javac.exe" | Select-Object -First 1
$java=Join-Path $compiler.DirectoryName 'java.exe'
$android=Join-Path $toolchain 'android-sdk\platforms\android-36\android.jar'
$json=Join-Path $testsRoot 'deps\json-20250517.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3EA61B2A06E31EDF1C91134FE9106B0EBB16628BE169F3DB75BC7A2B06B45796'){throw 'Host JSON dependency checksum mismatch'}
$src=Join-Path $project 'app\src\main\java\cn\local\manga'
$report=if($ReportDir){$ReportDir}else{Join-Path $testsRoot '1.0.0验证\本地覆盖'}
$out=Join-Path $report 'classes'
$hostFixtures=Join-Path $testsRoot '0.9.4验证\请求与设置\host'
New-Item -ItemType Directory -Path $out,$report -Force | Out-Null
$sources=@('AppSettings','ApiPresets','DetectorModels','ApiClient','ImageCleanup','RepairPixels','TranslationLog','TranslationTranscript','PartialTranslations','TranslationEngine','Region','RtDetrRegions','WhiteBubbleCleaner','GrayGlyphRepair','BubbleLayout','NearbyTextLayout','AutoTranslationQueue','TranslationStages','CleanupPlan','DetectionCache','CacheFiles','RenderedPageCache','PageOutcome','BrowserAddress','Typesetter','PageDraft','PageDraftStore','PageComposer','TextStyle','TextRenderer','BubbleStore') | ForEach-Object {Join-Path $src ($_.ToString()+'.java')}
$production=Join-Path $project 'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
if(!(Test-Path -LiteralPath "$production/cn/local/manga/PerformanceDiagnostics.class")){throw '请先运行构建.ps1，生成当前 release 字节码'}
$hashes=@{};foreach($file in ($sources+@(Join-Path $src 'SettingsActivity.java'))){$hashes[(Split-Path $file -Leaf)]=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
& $compiler.FullName '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -cp "$json;$android;$production" -d $out $sources "$testsRoot\api-host\android\os\SystemClock.java" "$testsRoot\v060-host\cn\local\manga\Detector.java" "$testsRoot\rtdetr-session-host\cn\local\manga\RtDetrDetector.java"
if($LASTEXITCODE -ne 0){throw 'Current production request/settings compilation failed'}
 $tests=@('PartialRetryChecks','LocalOverlayChecks','CropTextLayoutChecks')
& $compiler.FullName '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -cp "$out;$json;$android;$production" -d $out "$hostFixtures\android\content\Context.java" "$hostFixtures\android\graphics\Bitmap.java" "$testsRoot\host-geometry\android\graphics\Rect.java" "$testsRoot\v090-cleanup-host\android\graphics\BitmapFactory.java" "$testsRoot\v073-cache-host\android\util\Base64.java" ($tests|ForEach-Object {Join-Path $testsRoot ($_.ToString()+'.java')})
if($LASTEXITCODE -ne 0){throw 'Migrated request/settings fixture compilation failed'}
$counts=@{}
foreach($test in $tests){
 $fixture=Join-Path $report ($test+'-'+[guid]::NewGuid().ToString())
 $lines=& $java '-Dfile.encoding=UTF-8' -cp "$out;$json;$android;$production" "cn.local.manga.$test" $fixture 2>&1
 $exit=$LASTEXITCODE
 $lines|Set-Content -LiteralPath (Join-Path $report ($test+'.log')) -Encoding utf8
 $lines|Write-Output
 if($exit -ne 0){throw "$test failed"}
 if(($lines -join "`n") -notmatch '(\d+) checks passed'){throw "Missing check count for $test"}
 $counts[$test]=[int]$Matches[1]
}


foreach($file in ($sources+@(Join-Path $src 'SettingsActivity.java'))){if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hashes[(Split-Path $file -Leaf)]){throw ('Source changed during checks: '+$file)}}
@{passed=$true;checks=$counts;totalChecks=($counts.Values|Measure-Object -Sum).Sum;currentProductionEntryPoints=$true;localhostAndControlledConnectionsOnly=$true;externalApiUsed=$false;paidApiUsed=$false;androidUiRuntimeVerified=$false;androidKeystoreRuntimeVerified=$false;androidPixelRenderingVerified=$false;sourceHashes=$hashes;checkedAt=(Get-Date).ToUniversalTime().ToString('o')}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $report '结果.json') -Encoding utf8





