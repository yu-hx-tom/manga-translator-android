$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$compiler=Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1
$java=Join-Path $compiler.DirectoryName 'java.exe'
$sdk='D:/claude-code-space/.toolchains/android/android-sdk/platforms/android-36/android.jar'
$json=Join-Path $PSScriptRoot 'deps/json-20250517.jar'
$classes=Join-Path $project 'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$out=Join-Path $project '验证记录/UI重构检查/主机/classes'
$report=Split-Path $out -Parent
New-Item -ItemType Directory -Force $out | Out-Null
& $compiler.FullName '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -cp "$classes;$json;$sdk" -d $out (Join-Path $PSScriptRoot 'UiRefactorChecks.java') (Join-Path $PSScriptRoot 'host-geometry/android/graphics/Rect.java')
if($LASTEXITCODE -ne 0){throw 'UI model checks compilation failed'}
& $java '-Dfile.encoding=UTF-8' -cp "$out;$classes;$json;$sdk" cn.local.manga.UiRefactorChecks $report (Join-Path $project 'app/build/outputs/apk/debug/app-debug.apk') 2>&1 | Tee-Object -FilePath (Join-Path $report '结果.log')
if($LASTEXITCODE -ne 0){throw 'UI model checks failed'}

& $java '-Dfile.encoding=UTF-8' (Join-Path $PSScriptRoot 'IconGlyphChecks.java') $project 2>&1 | Tee-Object -FilePath (Join-Path $report '图标字符检查.log')
if($LASTEXITCODE -ne 0){throw 'Icon glyph regression check failed'}
& $java '-Dfile.encoding=UTF-8' (Join-Path $PSScriptRoot 'Ui110SourceChecks.java') $project 2>&1 | Tee-Object -FilePath (Join-Path $report '1.1.0组件检查.log')
if($LASTEXITCODE -ne 0){throw 'UI 1.1.0 resource/accessibility source checks failed'}
