param([switch]$Elevated, [switch]$Check)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Read-Default([string]$Prompt, [string]$Default) {
  $value = Read-Host "$Prompt [$Default]"
  if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
  return $value.Trim()
}
function Test-Package {
  $info = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'package-info'))
  if ($info.Count -ne 3 -or $info[0] -notmatch '^\d+\.\d+\.\d+(-dev(\.\d+)?)?$' -or $info[1] -ne 'windows') {
    throw '安装包信息无效，请重新下载完整 ZIP。'
  }
  $native = $env:PROCESSOR_ARCHITEW6432
  if (!$native) { $native = $env:PROCESSOR_ARCHITECTURE }
  $arch = switch ($native) { 'AMD64' {'amd64'} 'ARM64' {'arm64'} default { throw '不支持此 CPU 架构。' } }
  if ($info[2] -ne $arch) { throw "下载的包不适合此设备，请选择 Windows $arch 包。" }
  $seen = @{}
  foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot 'SHA256SUMS')) {
    if ($line -notmatch '^([a-f0-9]{64})  ([A-Za-z0-9_.-]+)$') { throw '安装包校验清单无效。' }
    $hash = $Matches[1]; $name = $Matches[2]
    if ($name -in @('.', '..') -or $seen.ContainsKey($name)) { throw '安装包文件名无效。' }
    $file = Join-Path $PSScriptRoot $name
    $item = Get-Item -Force -LiteralPath $file
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash -ine $hash) { throw "安装包校验失败：$name" }
    $seen[$name] = $true
  }
  foreach ($name in @('syncsrv-agent.exe', 'service-install.ps1', 'install.ps1', 'install.bat', 'uninstall.bat', 'agent-uninstall.ps1', 'agent-control.ps1', 'package-info')) {
    if (!$seen.ContainsKey($name)) { throw "安装包缺少文件：$name" }
  }
  $identity = & (Join-Path $PSScriptRoot 'syncsrv-agent.exe') version
  if ($LASTEXITCODE -ne 0 -or $identity -ne "$($info[0]) windows $arch") { throw '程序版本与安装包不一致。' }
  return $info[0]
}

if (!$Check) {
  $principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
  if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    try {
      $child = Start-Process -FilePath "$PSHOME\powershell.exe" -Verb RunAs -Wait -PassThru -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-Elevated')
      exit $child.ExitCode
    } catch { Write-Error '安装需要管理员权限。请允许系统权限提示，或右键 install.bat 以管理员身份运行。'; exit 1 }
  }
}
$result = 0
try {
  $version = Test-Package
  if ($Check) { Write-Host "安装包校验通过：$version"; exit 0 }
  if (Get-Service SyncSrvAgent -ErrorAction SilentlyContinue) {
    throw '此设备已安装 Agent。请运行 update.bat 升级，或 start.bat 启动；无需重新配对。'
  }
  Write-Host "`nSyncSrv 备份设备安装向导 · $version`n"
  Write-Host '只需填写连接信息；待备份目录在网页中配置。直接使用包内程序，不再下载安装文件。'
  $server = ''
  while (!$server) { $server = (Read-Host '备份服务器地址（例如 https://backup.example.com 或 http://192.168.50.20:18010）').Trim() }
  $name = Read-Default '设备名称' $env:COMPUTERNAME
  $installDir = Read-Default '程序安装目录（不要选择解压目录）' "$env:ProgramFiles\SyncSrvAgent"
  $dataDir = Read-Default '运行数据目录（身份、队列和日志，不是待备份目录）' (Join-Path $installDir 'data')
  $options = @{
    Server = $server; Version = $version; Name = $name; InstallDir = $installDir; DataDir = $dataDir
    Binary = (Join-Path $PSScriptRoot 'syncsrv-agent.exe')
  }
  if ($server.StartsWith('http://', [StringComparison]::OrdinalIgnoreCase)) {
    if ((Read-Host '此地址使用局域网 HTTP。确认仅在可信私有网络中测试？输入 Y 确认') -notmatch '^[Yy]$') { throw '已取消安装。' }
    $options.AllowHttp = $true
  }
  Write-Host "`n服务将以 LocalSystem 运行，并随 Windows 启动。下一步输入网页生成的一次性配对密钥。"
  & (Join-Path $PSScriptRoot 'service-install.ps1') @options
  Write-Host "`n安装成功。回到网页「备份设备」授权这台设备，再创建备份任务。" -ForegroundColor Green
} catch {
  $result = 1
  Write-Host "`n安装未完成：$($_.Exception.Message)" -ForegroundColor Red
  Write-Host '请保留上述错误。已有身份和队列不会自动删除；修正后可重试未完成的安装。'
} finally {
  if (!$Check) { Read-Host '按回车关闭安装向导' | Out-Null }
}
exit $result
