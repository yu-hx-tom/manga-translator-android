$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$compiler=Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1
$java=Join-Path $compiler.DirectoryName 'java.exe'
$sdk='D:/claude-code-space/.toolchains/android/android-sdk/platforms/android-36/android.jar'
$json=Join-Path $PSScriptRoot 'deps/json-20250517.jar'
$classes=Join-Path $project 'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$report=Join-Path $project '验证记录/1.0.2导入修复'
$out=Join-Path $report 'classes'
New-Item -ItemType Directory -Force $out | Out-Null
& $compiler.FullName '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -cp "$classes;$json;$sdk" -d $out (Join-Path $PSScriptRoot 'ImportCopyChecks.java') (Join-Path $PSScriptRoot '0.9.4验证/请求与设置/host/android/content/Context.java') (Join-Path $PSScriptRoot 'host-geometry/android/graphics/Rect.java')
if($LASTEXITCODE -ne 0){throw 'Import checks compilation failed'}
& $java '-Dfile.encoding=UTF-8' -cp "$out;$classes;$json;$sdk" cn.local.manga.ImportCopyChecks $report 2>&1 | Tee-Object -FilePath (Join-Path $report '结果.log')
if($LASTEXITCODE -ne 0){throw 'Import checks failed'}
