param([string]$Installer = (Join-Path $PSScriptRoot '../Backend/src/main/resources/agent/install.ps1'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  throw 'Run this ACL regression test in elevated Windows PowerShell.'
}
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Resolve-Path $Installer).Path, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
$helper = $ast.Find({ param($node)
  $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Set-AgentDirectoryAccess'
}, $true)
if ($null -eq $helper) { throw 'Installer ACL helper is missing.' }
# Load only the helper, never the install/enrollment/service actions.
. ([scriptblock]::Create($helper.Extent.Text))

function Assert-AgentAccess([string]$ItemPath, [bool]$Protected) {
  $acl = Get-Acl -LiteralPath $ItemPath
  if ($acl.AreAccessRulesProtected -ne $Protected) { throw "Unexpected inheritance: $ItemPath" }
  $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
  foreach ($sid in @('S-1-5-18', 'S-1-5-32-544')) {
    $allow = @($rules | Where-Object {
      $_.IdentityReference.Value -eq $sid -and $_.AccessControlType -eq 'Allow' -and
      ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl -and
      !($_.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly)
    })
    if ($allow.Count -eq 0) { throw "Missing effective full control for ${sid}: $ItemPath" }
  }
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) ('syncsrv-acl-test-' + [Guid]::NewGuid().ToString('N'))
$program = Join-Path $fixture 'Agent With Spaces'
$data = Join-Path $program 'data'
$external = Join-Path $fixture 'External Data'
try {
  New-Item -ItemType Directory -Path $data, $external -Force | Out-Null
  $probe = Join-Path $program 'probe.exe'
  Copy-Item -LiteralPath (Join-Path $env:SystemRoot 'System32/whoami.exe') -Destination $probe
  Set-Content -LiteralPath (Join-Path $data 'identity.json') -Value '{}'
  Set-Content -LiteralPath (Join-Path $external 'identity.json') -Value '{}'
  # Reproduce the broken installed EXE: an empty, protected file DACL.
  $empty = New-Object Security.AccessControl.FileSecurity
  $empty.SetSecurityDescriptorSddlForm('D:P')
  Set-Acl -LiteralPath $probe -AclObject $empty
  Set-AgentDirectoryAccess $program
  Set-AgentDirectoryAccess $data
  Set-AgentDirectoryAccess $external
  foreach ($item in @(Get-ChildItem -LiteralPath $fixture -Force -Recurse)) {
    Assert-AgentAccess $item.FullName $true
  }
  & $probe | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Executable does not run after ACL setup.' }
  $newFile = Join-Path $data 'new-event-file'
  Set-Content -LiteralPath $newFile -Value 'writable'
  Assert-AgentAccess $newFile $false
  Write-Host 'PASS: executable launch, explicit SYSTEM/admin rights, nested/custom data and new-file inheritance.'
} finally {
  if (Test-Path -LiteralPath $fixture) {
    Set-AgentDirectoryAccess $fixture
    Remove-Item -LiteralPath $fixture -Recurse -Force
  }
}
