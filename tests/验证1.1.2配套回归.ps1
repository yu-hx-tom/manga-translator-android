param([string]$ReportDir='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$report=if($ReportDir){$ReportDir}else{Join-Path $project '验证记录/1.1.2存储改造/配套回归'}
$out=Join-Path $report 'classes'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$compiler=(Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1).FullName
$java=Join-Path (Split-Path $compiler) 'java.exe'
$android='D:/claude-code-space/.toolchains/android/android-sdk/platforms/android-36/android.jar'
$json=Join-Path $PSScriptRoot 'deps/json-20250517.jar'
$production=Join-Path $project 'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
$fixtures=@((Join-Path $PSScriptRoot 'host-geometry/android/graphics/Rect.java'),(Join-Path $PSScriptRoot '0.9.4验证/请求与设置/host/android/content/Context.java'))
$fixtures+=@(Get-ChildItem (Join-Path $PSScriptRoot 'storage-host/android/system') -Filter '*.java' | ForEach-Object FullName)
$names=@('UiRefactorChecks','ImportCopyChecks','FrequentSitesChecks','SearchTermsChecks','AutoTranslationQueueChecks','QueueRetryChecks')
$sources=$names|ForEach-Object {Join-Path $PSScriptRoot ($_+'.java')}
& $compiler '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -cp "$production;$json;$android" -d $out $fixtures $sources
if($LASTEXITCODE -ne 0){throw 'Regression fixture compilation failed'}
foreach($name in $names){
    $folder=Join-Path $report $name
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    $arguments=if($name -eq 'SearchTermsChecks'){@()}else{@($folder,(Join-Path $project 'app/build/outputs/apk/release/app-release.apk'))}
    $lines=& $java '-Dfile.encoding=UTF-8' -cp "$out;$production;$json;$android" "cn.local.manga.$name" @arguments 2>&1
    $code=$LASTEXITCODE
    $lines | Tee-Object -FilePath (Join-Path $report ($name+'.log'))
    if($code -ne 0){throw "$name failed"}
}
foreach($name in @('IconGlyphChecks','Ui110SourceChecks')){
    $lines=& $java '-Dfile.encoding=UTF-8' (Join-Path $PSScriptRoot ($name+'.java')) $project 2>&1
    $code=$LASTEXITCODE
    $lines | Tee-Object -FilePath (Join-Path $report ($name+'.log'))
    if($code -ne 0){throw "$name failed"}
}
foreach($script in @('mock-api/check.cjs','mock-api/analyze-check.cjs','home-sites-checks.cjs')){
    $lines=& 'D:/WeGameApps/node/node.exe' (Join-Path $PSScriptRoot $script) $report 2>&1
    $code=$LASTEXITCODE
    $lines | Tee-Object -FilePath (Join-Path $report ((Split-Path $script -Leaf)+'.log'))
    if($code -ne 0){throw "$script failed"}
}
@{passed=$true;javaTests=$names;sourceChecks=@('IconGlyphChecks','Ui110SourceChecks');nodeChecks=@('mock-api/check','mock-api/analyze-check','home-sites-checks');phoneVerified=$false;checkedAt=(Get-Date).ToUniversalTime().ToString('o')} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $report '结果.json') -Encoding utf8
