param([string]$ReportDir='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$report=if($ReportDir){$ReportDir}else{Join-Path $project '验证记录/1.1.6/主机检查'}
$out=Join-Path $report ('classes-'+[guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $out | Out-Null
$compiler=(Get-ChildItem 'D:/claude-code-space/.toolchains/android/jdk/*/bin/javac.exe' | Select-Object -First 1).FullName
$java=Join-Path (Split-Path $compiler) 'java.exe'
$android='D:/claude-code-space/.toolchains/android/android-sdk/platforms/android-36/android.jar'
$python='C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$json=Join-Path $PSScriptRoot 'deps/json-20250517.jar'
$production=Join-Path $project 'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
$fixtures=@(Get-ChildItem (Join-Path $PSScriptRoot 'storage-host') -Recurse -Filter '*.java' | Where-Object Name -NE 'PerformanceDiagnostics.java' | ForEach-Object FullName)
$fixtures+=Join-Path $PSScriptRoot 'api-host/android/os/SystemClock.java'
$fixtures+=Join-Path $PSScriptRoot 'host-geometry/android/graphics/Rect.java'
$fixtures+=Join-Path $PSScriptRoot '0.9.4验证/请求与设置/host/android/graphics/Bitmap.java'
$names=@('Release116Checks','Storage115Checks','Storage112Checks','ApiPresetChecks','RefactorHostRuntimeChecks')
$fixtures+=@($names | ForEach-Object {Join-Path $PSScriptRoot ($_+'.java')})
$fixtures+=Join-Path $PSScriptRoot 'v073-cache-host/android/util/Base64.java'
& $compiler '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -cp "$production;$json;$android" -d $out $fixtures
if($LASTEXITCODE -ne 0){throw 'Fixture compilation failed'}
$bridge=Join-Path $PSScriptRoot 'storage-host/sqlite_bridge.py'
foreach($name in $names){
    $fixture=Join-Path $report ($name+'-'+[guid]::NewGuid())
    $lines=& $java '-Dfile.encoding=UTF-8' "-Dstorage.python=$python" "-Dstorage.bridge=$bridge" -cp "$out;$production;$json;$android" "cn.local.manga.$name" $fixture 2>&1
    $code=$LASTEXITCODE
    $lines | Tee-Object -FilePath (Join-Path $report ($name+'.log'))
    if($code -ne 0){throw "$name failed"}
}
@{passed=$true;suites=$names;androidRuntimeVerified=$false} | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $report '结果.json')
