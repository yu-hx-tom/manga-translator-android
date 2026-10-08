$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$shell=(Get-Process -Id $PID).Path
$build=Join-Path $project '构建.ps1'
$out=Join-Path $project 'build/工程门禁'
New-Item -ItemType Directory -Force -Path $out | Out-Null
if(@(& git.exe -C $project status --porcelain).Count){throw '门禁测试开始前要求工作区干净'}
$source=Join-Path $project 'app/src/main/java/cn/local/manga/Region.java'
$original=[IO.File]::ReadAllBytes($source)
$probe=Join-Path $project '.engineering-dirty-probe'
if(Test-Path -LiteralPath $probe){throw '测试探针路径已存在，拒绝覆盖'}
$checks=[ordered]@{}
try {
    $text=[Text.Encoding]::UTF8.GetString($original)
    $changed=[regex]::Replace($text,'(import [^;]+;)\s+(import [^;]+;)','$1 $2',1)
    if($changed -eq $text){throw '未找到可用于负向检查的两条 import'}
    [IO.File]::WriteAllText($source,$changed,[Text.UTF8Encoding]::new($false))
    $lines=& $shell -NoProfile -File $build 2>&1
    $code=$LASTEXITCODE
    $lines | Set-Content -LiteralPath (Join-Path $out '格式拒绝.log') -Encoding utf8
    $checks.formatRejected=($code -ne 0 -and ($lines -join "`n") -match '格式检查/格式化失败')
    if(!$checks.formatRejected){throw '格式错误没有正确阻止构建'}
} finally { [IO.File]::WriteAllBytes($source,$original) }
try {
    [IO.File]::WriteAllText($probe,'temporary acceptance probe')
    $lines=& $shell -NoProfile -File $build 2>&1
    $code=$LASTEXITCODE
    $lines | Set-Content -LiteralPath (Join-Path $out '未提交拒绝.log') -Encoding utf8
    $checks.dirtyRejected=($code -ne 0 -and ($lines -join "`n") -match '交付构建要求工作区干净')
    if(!$checks.dirtyRejected){throw '未提交改动没有正确阻止正式构建'}
    & $shell -NoProfile -File $build -AllowDirty *> (Join-Path $out 'AllowDirty.log')
    if($LASTEXITCODE -ne 0){throw 'AllowDirty 开发构建失败'}
    $latest=Get-ChildItem -LiteralPath (Join-Path $project 'build/构建记录') -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $record=Get-Content -LiteralPath (Join-Path $latest.FullName '构建.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $checks.allowDirtySucceeded=($record.status -eq 'success' -and $record.dirty -and $record.allowDirty -and !$record.deliverable)
    if(!$checks.allowDirtySucceeded){throw '开发构建缺少不可交付标记'}
    $checks.developmentBuildRecord=$latest.FullName
} finally { if(Test-Path -LiteralPath $probe){Remove-Item -LiteralPath $probe} }
if(@(& git.exe -C $project status --porcelain).Count){throw '门禁测试未恢复干净工作区'}
$checks.restoredClean=$true
$checks | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out '结果.json') -Encoding utf8
Write-Output '工程门禁通过：格式拒绝、dirty 拒绝、AllowDirty 成功且不可交付、原文件恢复。'
