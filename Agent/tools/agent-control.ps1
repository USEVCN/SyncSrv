param([ValidateSet('update','start','stop','restart','uninstall')][string]$Action = 'update')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  if ($Action -eq 'uninstall') {
    $child = Start-Process -FilePath "$PSHOME\powershell.exe" -Verb RunAs -Wait -PassThru -ArgumentList @(
      '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-Action', 'uninstall')
    exit $child.ExitCode
  }
  throw 'Run the BAT file as administrator (right click -> Run as administrator).'
}
$serviceName = 'SyncSrvAgent'
$service = Get-CimInstance Win32_Service -Filter "Name='SyncSrvAgent'"
if (!$service -and $Action -eq 'uninstall') { Write-Host 'SyncSrvAgent is already uninstalled. No files or data were removed.'; exit 0 }
if (!$service -or $service.PathName -notmatch '^"([^"\r\n]+\.exe)"\s+run\s+--data-dir\s+"([^"\r\n]+)"\s*$') {
  throw 'Existing SyncSrvAgent service not found or its command is unsupported. Install first; do not enroll again.'
}
$exe = $Matches[1]
$dataDir = $Matches[2]
$installDir = Split-Path -Parent $exe
foreach ($path in @($exe, $installDir, $dataDir)) {
  if ((Get-Item -Force -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse points are not supported.' }
}
$lock = $null
$stage = $null
$backup = $null
$wasRunning = $service.State -eq 'Running'
$changed = $false
$log = Join-Path $installDir 'upgrade.log'
function Write-UpgradeLog([string]$Message) {
  $line = "$(Get-Date -Format o) $Message"
  if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).Length -gt 1MB) { Move-Item -LiteralPath $log -Destination "$log.previous" -Force }
  Add-Content -LiteralPath $log -Value $line -Encoding UTF8
  Write-Host $line
}
function Stop-Agent {
  $current = Get-Service $serviceName
  try {
    if ($current.Status -ne 'Stopped') {
      $current.Stop()
      $current.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(90))
    }
  } finally { $current.Dispose() }
}
function Start-Agent {
  Start-Service $serviceName
  (Get-Service $serviceName).WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
  Start-Sleep -Seconds 3
  if ((Get-Service $serviceName).Status -ne 'Running') { throw 'Service exited after startup; inspect service.log.' }
}
try {
  $lockPath = Join-Path $installDir '.management.lock'
  if ((Test-Path -LiteralPath $lockPath) -and ((Get-Item -Force -LiteralPath $lockPath).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unsafe lock path.' }
  $lock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None')
  $bootstrap = Join-Path $PSScriptRoot 'syncsrv-agent.exe'
  if ((Test-Path -LiteralPath (Join-Path $installDir 'upgrade-state.json')) -or (Test-Path -LiteralPath (Join-Path $installDir 'upgrade-state.json.previous'))) {
    $recovery = & $bootstrap upgrade-transaction --action inspect --install-dir $installDir --data-dir $dataDir
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect interrupted upgrade.' }
    if ($recovery -in @('RECOVER_RUNNING','RECOVER_STOPPED')) {
      Stop-Agent
      & $bootstrap upgrade-transaction --action rollback --install-dir $installDir --data-dir $dataDir
      if ($LASTEXITCODE -ne 0) { throw 'Interrupted upgrade recovery failed.' }
      if ($recovery -eq 'RECOVER_RUNNING') { Start-Agent }
      Write-UpgradeLog 'INTERRUPTED_UPGRADE_RECOVERED'
    }
  }
  if ($Action -eq 'uninstall') { . (Join-Path $PSScriptRoot 'agent-uninstall.ps1'); Uninstall-Agent; exit 0 }
  if ($Action -eq 'stop') { Stop-Agent; Write-UpgradeLog 'SERVICE_STOPPED'; exit 0 }
  if ($Action -eq 'start') { Start-Agent; Write-UpgradeLog 'SERVICE_STARTED'; exit 0 }
  if ($Action -eq 'restart') { Stop-Agent; Start-Agent; Write-UpgradeLog 'SERVICE_RESTARTED'; exit 0 }
  $bootstrap = Join-Path $PSScriptRoot 'syncsrv-agent.exe'
  if (!(Test-Path -LiteralPath $bootstrap)) { throw 'Extract the complete upgrade ZIP before running update.bat.' }
  $oldIdentity = & $exe version
  if ($LASTEXITCODE -ne 0 -or $oldIdentity -notmatch '^(\S+) windows (amd64|arm64)$') { throw 'Cannot read installed version.' }
  $oldVersion = $Matches[1]
  $arch = $Matches[2]
  $stage = Join-Path $installDir ('.upgrade-' + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $stage | Out-Null
  $stageAcl = New-Object Security.AccessControl.DirectorySecurity
  $stageAcl.SetSecurityDescriptorSddlForm('D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)')
  Set-Acl -LiteralPath $stage -AclObject $stageAcl
  Write-UpgradeLog "CHECKING from=$oldVersion"
  $newVersion = & $bootstrap prepare-update --data-dir $dataDir --current-version $oldVersion --output-dir $stage
  if ($LASTEXITCODE -ne 0) { throw 'Download/check failed. Existing service has not been stopped.' }
  if ($newVersion -eq 'UNCHANGED') { Write-UpgradeLog 'NO_NEWER_VERSION'; exit 0 }
  if ($newVersion -notmatch '^\d+\.\d+\.\d+(-dev(\.\d+)?)?$') { throw 'Invalid upgrade result.' }
  $new = Join-Path $stage 'syncsrv-agent.exe'
  $identity = & $new version
  if ($LASTEXITCODE -ne 0 -or $identity -ne "$newVersion windows $arch") { throw 'Downloaded program identity mismatch.' }
  $acl = Get-Acl -LiteralPath $exe
  $backup = "$exe.backup-$(Get-Date -Format yyyyMMddHHmmss)-$([Guid]::NewGuid().ToString('N'))"
  Copy-Item -LiteralPath $exe -Destination $backup
  Set-Acl -LiteralPath $backup -AclObject $acl
  Write-UpgradeLog "VERIFIED from=$oldVersion to=$newVersion backup=$backup"
  Stop-Agent
  & $new upgrade-transaction --action begin --install-dir $installDir --data-dir $dataDir --backup $backup --mode windows "--running=$($wasRunning.ToString().ToLowerInvariant())"
  if ($LASTEXITCODE -ne 0) { throw 'Unable to snapshot stopped device state.' }
  & $new upgrade-transaction --action activate --install-dir $installDir --data-dir $dataDir
  if ($LASTEXITCODE -ne 0) { throw 'Unable to persist activation journal.' }
  $changed = $true
  & $new upgrade-transaction --action replace --install-dir $installDir --data-dir $dataDir --replacement $new
  if ($LASTEXITCODE -ne 0) { throw "Atomic program replacement failed." }
  Set-Acl -LiteralPath $exe -AclObject $acl
  if ($wasRunning) {
    Remove-Item -LiteralPath (Join-Path $dataDir "health.json") -Force -ErrorAction SilentlyContinue
    Start-Agent
    & $exe health-check --data-dir $dataDir --expected-version $newVersion --timeout 120
    if ($LASTEXITCODE -ne 0) { throw "New Agent did not receive center acknowledgement." }
  }
  & $exe install-tools --install-dir $installDir --data-dir $dataDir --mode windows
  if ($LASTEXITCODE -ne 0) { throw 'Unable to install management scripts.' }
  & $exe upgrade-transaction --action commit --install-dir $installDir --data-dir $dataDir
  if ($LASTEXITCODE -ne 0) { throw "Unable to finalize upgrade journal." }
  Write-UpgradeLog "UPGRADED from=$oldVersion to=$newVersion running=$wasRunning"
  Write-Host 'Refresh the center device version and verify a small-file backup. Device identity and event queue are preserved.'
} catch {
  $failure = $_
  if ($changed -and $backup) {
    try {
      Stop-Agent
      & (Join-Path $stage "syncsrv-agent.exe") upgrade-transaction --action rollback --install-dir $installDir --data-dir $dataDir
      if ($LASTEXITCODE -ne 0) { throw "Full rollback was incomplete; inspect upgrade-state.json." }
      Copy-Item -LiteralPath $backup -Destination $exe -Force
      Set-Acl -LiteralPath $exe -AclObject $acl
      if ($wasRunning) { Start-Agent }
      Write-UpgradeLog 'ROLLED_BACK to_previous_binary'
    } catch { Write-Warning "Automatic rollback failed. Restore $backup after stopping the service. Details: $_" }
  }
  try { Write-UpgradeLog "FAILED $($failure.Exception.Message)" } catch {}
  throw $failure
} finally {
  if ($stage -and (Test-Path -LiteralPath $stage)) { Remove-Item -LiteralPath $stage -Recurse -Force }
  if ($lock) { $lock.Dispose() }
}
