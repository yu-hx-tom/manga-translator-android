param([string]$ReportDir='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$report=if($ReportDir){$ReportDir}else{Join-Path $project 'build/typography-checks'}
$out=Join-Path $report 'classes'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$compiler=(Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1).FullName
$java=Join-Path (Split-Path $compiler) 'java.exe'
$src=Join-Path $project 'app/src/main/java/cn/local/manga'
$checks=@('BubbleFontSizeChecks','AdaptiveTypographyChecks','CropTextLayoutChecks')
$sources=@('BubbleLayout','NearbyTextLayout') | ForEach-Object {Join-Path $src ($_+'.java')}
$sources+=@($checks | ForEach-Object {Join-Path $PSScriptRoot ($_+'.java')})
& $compiler -encoding UTF-8 -d $out $sources
if($LASTEXITCODE -ne 0){throw 'Typography compilation failed'}
foreach($check in $checks){
    & $java '-Dfile.encoding=UTF-8' -cp $out ('cn.local.manga.'+$check) 2>&1 | Tee-Object -FilePath (Join-Path $report ($check+'.log'))
    if($LASTEXITCODE -ne 0){throw ($check+' failed')}
}
