$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$out=Join-Path $project '验证记录/1.0.9常用网站/classes'
New-Item -ItemType Directory -Force $out | Out-Null
$compiler=Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1
$java=Join-Path $compiler.DirectoryName 'java.exe'
$json=Join-Path $PSScriptRoot 'deps/json-20250517.jar'
& $compiler.FullName -encoding UTF-8 -cp $json -d $out (Join-Path $project 'app/src/main/java/cn/local/manga/LibraryStore.java') (Join-Path $project 'app/src/main/java/cn/local/manga/BrowserAddress.java') (Join-Path $PSScriptRoot 'FrequentSitesChecks.java')
if($LASTEXITCODE -ne 0){throw 'Compilation failed'}
& $java '-Dfile.encoding=UTF-8' -cp "$out;$json" cn.local.manga.FrequentSitesChecks (Split-Path $out -Parent)
if($LASTEXITCODE -ne 0){throw 'Site persistence checks failed'}
& 'D:/WeGameApps/node/node.exe' (Join-Path $PSScriptRoot 'home-sites-checks.cjs')
if($LASTEXITCODE -ne 0){throw 'Homepage interaction checks failed'}
