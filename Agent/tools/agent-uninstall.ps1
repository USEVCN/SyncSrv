# Loaded by agent-control.ps1 after service identity checks and the management lock.
function Uninstall-Agent {
  Write-UpgradeLog 'UNINSTALL_REQUESTED'
  Stop-Agent
  & sc.exe delete $serviceName | Out-Host
  if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1072) { throw "Service removal failed: $LASTEXITCODE" }
  Write-UpgradeLog "SERVICE_UNINSTALLED retained_data=$dataDir"
  Write-Host 'Service removed (or marked for removal by Windows). Program, identity, queue, logs and backup files are retained.'
  Write-Host "Program: $installDir"
  Write-Host "Data: $dataDir"
  if (Get-CimInstance Win32_Service -Filter "Name='SyncSrvAgent'") {
    Write-Warning 'Windows still holds a service handle. Close the Services window; restart Windows if removal remains pending.'
  }
}
