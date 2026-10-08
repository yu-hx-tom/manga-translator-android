param([switch]$IncludeDebug)
$ErrorActionPreference='Stop'
$project=$PSScriptRoot
$buildHome=Join-Path $project 'build/构建用户'
$env:ANDROID_USER_HOME=Join-Path $buildHome '.android'
$env:ANDROID_SDK_HOME=$buildHome
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME|Out-Null
$originalKey='C:/Users/Administrator/.android/debug.keystore'
if(-not (Test-Path -LiteralPath $originalKey)){throw '原调试签名文件不可用，停止构建以免生成无法覆盖安装的签名'}
Copy-Item -LiteralPath $originalKey -Destination (Join-Path $env:ANDROID_USER_HOME 'debug.keystore')
$env:JAVA_OPTS='-Xms16m -Xmx64m -Xss256k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=16m -Dfile.encoding=UTF-8'
$env:JAVA_OPTS+=' -Duser.home="'+$buildHome+'"'
$daemonOptions='-Dorg.gradle.jvmargs=-Xms16m -Xmx512m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 -Duser.home="'+$buildHome+'"'
& 'D:/codex-home/skills/android-dev/scripts/android.ps1' build -ProjectPath $project -Offline --max-workers=1 $daemonOptions lintRelease lintDebug assembleRelease assembleDebugAndroidTest
if($LASTEXITCODE -eq 0 -and $IncludeDebug){ & 'D:/codex-home/skills/android-dev/scripts/android.ps1' build -ProjectPath $project -Offline --max-workers=1 $daemonOptions assembleDebug }
exit $LASTEXITCODE

