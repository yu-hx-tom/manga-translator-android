$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$compiler=Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1
$java=Join-Path $compiler.DirectoryName 'java.exe'
$report=Join-Path $project '验证记录/1.0.4队列重试'
$out=Join-Path $report 'classes'
New-Item -ItemType Directory -Force $out | Out-Null
& $compiler.FullName '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -d $out (Join-Path $project 'app/src/main/java/cn/local/manga/AutoTranslationQueue.java') (Join-Path $PSScriptRoot 'AutoTranslationQueueChecks.java') (Join-Path $PSScriptRoot 'QueueRetryChecks.java')
if($LASTEXITCODE -ne 0){throw 'Queue checks compilation failed'}
foreach($test in @('AutoTranslationQueueChecks','QueueRetryChecks')){
    & $java '-Dfile.encoding=UTF-8' -cp $out "cn.local.manga.$test" 2>&1 | Tee-Object -FilePath (Join-Path $report "$test.log")
    if($LASTEXITCODE -ne 0){throw "$test failed"}
}
