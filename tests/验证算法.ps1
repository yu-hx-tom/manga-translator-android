$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$compiler=Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1
$java=Join-Path $compiler.DirectoryName 'java.exe'
$src=Join-Path $project 'app/src/main/java/cn/local/manga'
$out=Join-Path $project '验证记录/算法检查/classes'
New-Item -ItemType Directory -Force $out | Out-Null
$sources=@('WhiteBubbleCleaner','GrayGlyphRepair','BubbleLayout','CleanupPlan','CacheFiles','RenderedPageCache','PageOutcome','TranslationTranscript') | ForEach-Object {Join-Path $src ($_+'.java')}
$production=Join-Path $project 'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
$support=$production+';'+(Join-Path $PSScriptRoot 'deps/json-20250517.jar')+';D:/claude-code-space/.toolchains/android/android-sdk/platforms/android-36/android.jar'
if(!(Test-Path -LiteralPath "$production/cn/local/manga/PerformanceDiagnostics.class")){throw '请先运行构建.ps1'}
$checks=@('TargetedBubbleChecks','CleanupPlanChecks','RenderedPageCacheChecks')
$tests=$checks | ForEach-Object {Join-Path $PSScriptRoot ($_+'.java')}
& $compiler.FullName -encoding UTF-8 -cp $support -d $out $sources $tests
if($LASTEXITCODE -ne 0){throw 'Algorithm checks compilation failed'}
foreach($test in $checks){
 $lines=& $java -cp "$out;$support" ('cn.local.manga.'+$test) (Join-Path $project '验证记录/算法检查') 2>&1
 $code=$LASTEXITCODE
 $lines | Tee-Object -FilePath (Join-Path $project ('验证记录/算法检查/'+$test+'.log'))
 if($code -ne 0){throw ($test+' failed')}
}


