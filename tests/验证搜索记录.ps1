$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$compiler=Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1
$java=Join-Path $compiler.DirectoryName 'java.exe'
$report=Join-Path $project '验证记录/1.0.8连续搜索'
$out=Join-Path $report 'classes'
New-Item -ItemType Directory -Force $out | Out-Null
$json=Join-Path $PSScriptRoot 'deps/json-20250517.jar'
& $compiler.FullName '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -cp $json -d $out (Join-Path $project 'app/src/main/java/cn/local/manga/SearchTerms.java') (Join-Path $PSScriptRoot 'SearchTermsChecks.java')
if($LASTEXITCODE -ne 0){throw 'Search checks compilation failed'}
& 'D:/WeGameApps/node/node.exe' (Join-Path $PSScriptRoot 'browser-search-sequence.cjs')
if($LASTEXITCODE -ne 0){throw 'Sequential browser search failed'}
& $java '-Dfile.encoding=UTF-8' -cp "$out;$json" cn.local.manga.SearchTermsChecks (Join-Path $report '连续搜索事件.json') 2>&1 | Tee-Object -FilePath (Join-Path $report '记录规则.log')
if($LASTEXITCODE -ne 0){throw 'Search checks failed'}
