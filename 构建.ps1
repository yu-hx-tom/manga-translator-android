param([switch]$IncludeDebug,[switch]$AllowDirty)
$ErrorActionPreference='Stop'
$project=$PSScriptRoot
function Invoke-RepositoryGit {
    $value=& git.exe -C $project @args
    if($LASTEXITCODE -ne 0){throw 'Git 查询失败'}
    return $value
}
$commit=Invoke-RepositoryGit @('rev-parse','HEAD')
$state=@(Invoke-RepositoryGit @('status','--porcelain','--untracked-files=all'))
$dirty=$state.Count -gt 0
if($dirty -and -not $AllowDirty){throw '交付构建要求工作区干净，请先提交；开发验证使用 -AllowDirty'}
$dir=Join-Path $project ('build/构建记录/'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$gradle=Get-Content -LiteralPath (Join-Path $project 'app/build.gradle') -Raw -Encoding UTF8
if($gradle -notmatch "versionName\s+'([^']+)'"){throw '版本名缺失'}
$versionName=$Matches[1]
if($gradle -notmatch 'versionCode\s+(\d+)'){throw '版本码缺失'}
$versionCode=[int]$Matches[1]
$record=[ordered]@{commit=$commit;dirty=$dirty;allowDirty=[bool]$AllowDirty;deliverable=(!$dirty -and !$AllowDirty);status='running';versionName=$versionName;versionCode=$versionCode;startedUtc=(Get-Date).ToUniversalTime().ToString('o');workingTree=$state;sourceHashes=@{}}
$tracked=@(Invoke-RepositoryGit @('ls-files'))
foreach($file in $tracked){$path=Join-Path $project $file;if(Test-Path -LiteralPath $path -PathType Leaf){$record.sourceHashes[$file]=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}}
$recordPath=Join-Path $dir '构建.json'
Start-Transcript -LiteralPath (Join-Path $dir '构建.log') | Out-Null
try {
    Write-Host "构建提交：$commit；dirty=$dirty；AllowDirty=$AllowDirty"
    if($AllowDirty){Write-Warning '开发构建：含未提交改动或允许未提交改动，不可作为交付'}
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
if($LASTEXITCODE -ne 0){throw 'Gradle 构建失败'}


    if((Invoke-RepositoryGit @('rev-parse','HEAD')) -ne $commit -or (@(Invoke-RepositoryGit @('status','--porcelain','--untracked-files=all')) -join "`n") -ne ($state -join "`n")){throw '构建期间提交或工作区状态改变'}
    foreach($file in $record.sourceHashes.Keys){if((Get-FileHash -LiteralPath (Join-Path $project $file)).Hash.ToLowerInvariant() -ne $record.sourceHashes[$file]){throw "构建期间文件改变：$file"}}
    $record.apkSha256=(Get-FileHash -LiteralPath (Join-Path $project 'app/build/outputs/apk/release/app-release.apk')).Hash.ToLowerInvariant()
    $record.status='success'
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $recordPath -Encoding utf8
    & (Join-Path $project '工具/打包验证.ps1') -BuildRecord $recordPath
} catch {
    $record.status='failed';$record.deliverable=$false;$record.error=$_.Exception.Message
    throw
} finally {
    $record.finishedUtc=(Get-Date).ToUniversalTime().ToString('o')
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $recordPath -Encoding utf8
    Stop-Transcript | Out-Null
    Write-Host "构建记录：$recordPath"
}
