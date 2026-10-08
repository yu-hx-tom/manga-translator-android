param([string]$ReportDir='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$report=if($ReportDir){$ReportDir}else{Join-Path $project '验证记录/1.1.2存储改造/主机存储检查'}
$out=Join-Path $report 'classes'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$compiler=(Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1).FullName
$java=Join-Path (Split-Path $compiler) 'java.exe'
$android='D:/claude-code-space/.toolchains/android/android-sdk/platforms/android-36/android.jar'
$python='C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$json=Join-Path $PSScriptRoot 'deps/json-20250517.jar'
$production=Join-Path $project 'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
$fixtures=@(Get-ChildItem (Join-Path $PSScriptRoot 'storage-host') -Recurse -Filter '*.java' | ForEach-Object FullName)
$fixtures+=Join-Path $PSScriptRoot 'host-geometry/android/graphics/Rect.java'
$fixtures+=Join-Path $PSScriptRoot '0.9.4验证/请求与设置/host/android/graphics/Bitmap.java'
$fixtures+=Join-Path $PSScriptRoot 'Storage112Checks.java'
& $compiler '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -cp "$production;$json;$android" -d $out $fixtures
if($LASTEXITCODE -ne 0){throw 'Storage fixtures compilation failed'}
$fixture=Join-Path $report ('fixture-'+[guid]::NewGuid().ToString())
$bridge=Join-Path $PSScriptRoot 'storage-host/sqlite_bridge.py'
$lines=& $java '-Dfile.encoding=UTF-8' "-Dstorage.python=$python" "-Dstorage.bridge=$bridge" -cp "$out;$production;$json;$android" cn.local.manga.Storage112Checks $fixture 2>&1
$code=$LASTEXITCODE
$lines | Tee-Object -FilePath (Join-Path $report '检查.log')
if($code -ne 0){throw 'Storage checks failed'}
Copy-Item -LiteralPath (Join-Path $fixture 'result.json') -Destination (Join-Path $report '结果.json') -Force
