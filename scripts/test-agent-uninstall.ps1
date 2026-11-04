# Run with Windows PowerShell 5.1+ or pwsh. Mocked helpers never control real services.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
foreach ($name in @('agent-control.ps1', 'agent-uninstall.ps1')) {
  $tokens = $null; $parseErrors = $null
  [Management.Automation.Language.Parser]::ParseFile((Join-Path $root "Agent/tools/$name"), [ref]$tokens, [ref]$parseErrors) | Out-Null
  if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
}
. (Join-Path $root 'Agent/tools/agent-uninstall.ps1')
$serviceName = 'SyncSrvAgent'; $installDir = 'C:\Program Files\SyncSrvAgent'; $dataDir = "$installDir\data"
function Write-UpgradeLog([string]$Message) { $script:events += $Message }
function Stop-Agent {
  $script:events += 'stop'
  if ($script:stopFails) { throw 'Stop timed out' }
}
function sc.exe([string]$Action, [string]$Name) {
  if ($Action -ne 'delete' -or $Name -ne 'SyncSrvAgent') { throw 'Unexpected service mutation' }
  $script:events += 'delete'; $global:LASTEXITCODE = $script:deleteCode
}
function Get-CimInstance { param($ClassName, $Filter); return $null }
foreach ($case in @('success', 'stop-failed', 'delete-failed', 'already-marked')) {
  $script:events = @(); $script:stopFails = $case -eq 'stop-failed'
  $script:deleteCode = if ($case -eq 'delete-failed') { 5 } elseif ($case -eq 'already-marked') { 1072 } else { 0 }
  $failure = $null
  try { Uninstall-Agent } catch { $failure = $_ }
  $shouldFail = $case -in @('stop-failed','delete-failed')
  if (($null -ne $failure) -ne $shouldFail) { throw "Unexpected result: $case" }
  if ($script:stopFails -and $script:events -contains 'delete') { throw 'Deleted registration before a safe stop' }
  $completed = @($script:events | Where-Object { $_ -like 'SERVICE_UNINSTALLED*' }).Count -gt 0
  if ($completed -eq $shouldFail) { throw "Incorrect success audit: $case" }
}
Write-Host 'PASS: script parsing, service removal, stop-failure retention and failed-removal audit.'
