param([switch]$Check)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$jar=Join-Path $PSScriptRoot 'google-java-format-1.28.0-all-deps.jar'
if((Get-FileHash -LiteralPath $jar).Hash -ne '32342e7c1b4600f80df3471da46aee8012d3e1445d5ea1be1fb71289b07cc735'){throw '格式化工具校验失败'}
$toolchain=if($env:CODEX_ANDROID_TOOLCHAIN){$env:CODEX_ANDROID_TOOLCHAIN}else{'D:/claude-code-space/.toolchains/android'}
$jdk=Get-ChildItem (Join-Path $toolchain 'jdk') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'bin/java.exe')} | Sort-Object Name -Descending | Select-Object -First 1
if(!$jdk){throw '共享 JDK 不存在'}
$exclude=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot '格式化排除.txt') -Encoding UTF8 | Where-Object {$_ -and !$_.StartsWith('#')})
$files=@('app/src/main/java','app/src/androidTest/java','tests') | ForEach-Object {
    Get-ChildItem -LiteralPath (Join-Path $project $_) -Recurse -Filter '*.java' -File
} | Where-Object { $_.FullName.Substring($project.Length+1).Replace('\','/') -notin $exclude } | Sort-Object FullName
if(!$files){throw '没有找到 Java 文件'}
$arguments=@('--aosp')
if($Check){$arguments+=@('--dry-run','--set-exit-if-changed')}else{$arguments+='--replace'}
# @args 文件避免 Windows 命令行长度限制；路径带中文或空格也可读取。
$cache=Join-Path $project 'build/format'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$argFile=Join-Path $cache ([guid]::NewGuid().ToString('N')+'.args')
$arguments+=@($files | ForEach-Object {$_.FullName.Replace('\','/')})
[IO.File]::WriteAllLines($argFile,$arguments,[Text.UTF8Encoding]::new($false))
$exports=@('api','code','file','parser','tree','util') | ForEach-Object {"--add-exports=jdk.compiler/com.sun.tools.javac.$_=ALL-UNNAMED"}
$clock=[Diagnostics.Stopwatch]::StartNew()
try {
    # 此版本在折分长字符串后，第二轮可能继续调整 AOSP 续行缩进。
    # 默认运行两轮官方 formatter，再只读验证；绝不手工改写其输出。
    $rounds=if($Check){1}else{3}
    for($round=1;$round -le $rounds;$round++){
        if(!$Check -and $round -eq 3){
            $verifyArgs=@('--aosp','--dry-run','--set-exit-if-changed')+@($files | ForEach-Object {$_.FullName.Replace('\','/')})
            [IO.File]::WriteAllLines($argFile,$verifyArgs,[Text.UTF8Encoding]::new($false))
        }
        $output=& (Join-Path $jdk.FullName 'bin/java.exe') '-Dfile.encoding=UTF-8' @exports -jar $jar "@$argFile" 2>&1
        $code=$LASTEXITCODE
        $output | Write-Output
        if(($output -join "`n") -match 'Skipping non-Java file:'){throw '工具跳过了输入文件，不能认定通过'}
        if($code -ne 0){throw '代码格式检查/格式化失败；请运行 工具/格式化.ps1，检查上方文件及诊断'}
    }
    Write-Host ('Java 格式'+$(if($Check){'检查'}else{'化'})+'通过：'+$files.Count+' 个文件，'+[Math]::Round($clock.Elapsed.TotalSeconds,2)+' 秒')
} finally {
    Remove-Item -LiteralPath $argFile
}
