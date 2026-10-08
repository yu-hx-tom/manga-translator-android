$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$compiler=Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1
$java=Join-Path $compiler.DirectoryName 'java.exe'
$report=Join-Path $project '验证记录/1.0.3缓存管理'
$out=Join-Path $report 'classes'
New-Item -ItemType Directory -Force $out | Out-Null
& $compiler.FullName '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -d $out (Join-Path $project 'app/src/main/java/cn/local/manga/CacheStorage.java') (Join-Path $PSScriptRoot 'CacheStorageChecks.java')
if($LASTEXITCODE -ne 0){throw 'Cache checks compilation failed'}
& $java '-Dfile.encoding=UTF-8' -cp $out cn.local.manga.CacheStorageChecks $report 2>&1 | Tee-Object -FilePath (Join-Path $report '结果.log')
if($LASTEXITCODE -ne 0){throw 'Cache checks failed'}
