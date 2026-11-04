param(
  [Parameter(Mandatory=$true)][string]$Server,
  [Parameter(Mandatory=$true)][string]$Version,
  [string]$InstallDir = "$env:ProgramFiles\SyncSrvAgent",
  [string]$DataDir = "",
  [string]$Binary = "",
  [string]$TokenFile = "",
  [string]$Name = "",
  [switch]$AllowHttp
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  throw 'Run the installer in an elevated PowerShell terminal.'
}
function Set-AgentDirectoryAccess([string]$Directory) {
  # Apply a complete DACL in one operation. Directories inherit to children;
  # regular files need explicit rights on the file itself, without inheritance flags.
  $items = @((Get-Item -Force -LiteralPath $Directory)) + @(Get-ChildItem -Force -Recurse -LiteralPath $Directory)
  foreach ($item in $items) {
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
      throw "Refusing to change permissions on a reparse point: $($item.FullName)"
    }
    if ($item.PSIsContainer) {
      $acl = New-Object Security.AccessControl.DirectorySecurity
      $acl.SetSecurityDescriptorSddlForm('D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)')
    } else {
      $acl = New-Object Security.AccessControl.FileSecurity
      $acl.SetSecurityDescriptorSddlForm('D:P(A;;FA;;;SY)(A;;FA;;;BA)')
    }
    Set-Acl -LiteralPath $item.FullName -AclObject $acl
  }
}
$origin = [Uri]$Server
if (($origin.Scheme -ne 'https' -and !($AllowHttp -and $origin.Scheme -eq 'http')) -or $origin.UserInfo -or $origin.Query -or $origin.Fragment -or $origin.AbsolutePath -ne '/') {
  throw 'Use an HTTPS origin, or -AllowHttp for explicit private IP development.'
}
$Server = $Server.TrimEnd('/')
if ($Version -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$') { throw 'Specify a pinned release version.' }
$nativeArch = $env:PROCESSOR_ARCHITEW6432
if (!$nativeArch) { $nativeArch = $env:PROCESSOR_ARCHITECTURE }
$arch = switch ($nativeArch) { 'AMD64' {'amd64'} 'ARM64' {'arm64'} default {throw 'Unsupported Windows architecture.'} }
$InstallDir = [IO.Path]::GetFullPath($InstallDir)
if ([string]::IsNullOrWhiteSpace($DataDir)) { $DataDir = Join-Path $InstallDir 'data' }
$DataDir = [IO.Path]::GetFullPath($DataDir)
$pendingFile = Join-Path $InstallDir '.install-pending.json'
$resume = $false
if (Test-Path -LiteralPath $pendingFile) {
  $pending = Get-Content -LiteralPath $pendingFile -Raw | ConvertFrom-Json
  $resume = $pending.server -eq $Server -and $pending.version -eq $Version -and
    $pending.installDir -eq $InstallDir -and $pending.dataDir -eq $DataDir
  if (!$resume) { throw 'Incomplete installation belongs to different settings. Keep its data and use the original settings.' }
}
foreach ($directory in @($InstallDir, $DataDir)) {
  if ($directory -match '["\r\n]' -or $directory.StartsWith('\\') -or $directory -eq [IO.Path]::GetPathRoot($directory)) {
    throw 'Choose dedicated absolute local directories.'
  }
  if (Test-Path -LiteralPath $directory) {
    $item = Get-Item -LiteralPath $directory
    if (!$item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        (!$resume -and @(Get-ChildItem -Force -LiteralPath $directory).Count -gt 0)) {
      throw "Directory must be new or empty: $directory"
    }
  }
}
$left = $InstallDir.TrimEnd('\') + '\'
$right = $DataDir.TrimEnd('\') + '\'
# Data may be a child of InstallDir, but must not contain the executable directory.
if ($left.StartsWith($right, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Data directory must differ from, and must not contain, the installation directory.'
}
if (Get-Service -Name SyncSrvAgent -ErrorAction SilentlyContinue) {
  throw 'Service already exists. Run update.bat from the complete upgrade ZIP instead of reinstalling.'
}
$tempDir = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDir | Out-Null
try {
  $download = Join-Path $tempDir 'syncsrv-agent.exe'
  $asset = "syncsrv-agent-windows-$arch.exe"
  if ($Binary) { Copy-Item -LiteralPath $Binary -Destination $download }
  else {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -MaximumRedirection 0 -Uri "$Server/agent/releases/$Version/$asset" -OutFile $download
    $hashFile = Join-Path $tempDir 'checksum.txt'
    Invoke-WebRequest -UseBasicParsing -MaximumRedirection 0 -Uri "$Server/agent/releases/$Version/$asset.sha256" -OutFile $hashFile
    $expected = ((Get-Content -LiteralPath $hashFile -Raw).Trim() -split '\s+')[0]
    if ($expected -notmatch '^[a-f0-9]{64}$' -or (Get-FileHash -Algorithm SHA256 -LiteralPath $download).Hash -ine $expected) {
      throw 'Release checksum mismatch.'
    }
  }
  $reported = & $download version
  if ($LASTEXITCODE -ne 0 -or $reported -ne "$Version windows $arch") { throw 'Unexpected binary identity.' }
  New-Item -ItemType Directory -Force -Path $InstallDir, $DataDir | Out-Null
  $exe = Join-Path $InstallDir 'syncsrv-agent.exe'
  Copy-Item -LiteralPath $download -Destination $exe
  @{server=$Server; version=$Version; installDir=$InstallDir; dataDir=$DataDir} |
    ConvertTo-Json | Set-Content -LiteralPath $pendingFile -Encoding UTF8
  Set-AgentDirectoryAccess $InstallDir
  Set-AgentDirectoryAccess $DataDir
  $enrollment = @('enroll', '--server', $Server, '--data-dir', $DataDir, '--name', $Name)
  if ($AllowHttp) { $enrollment += '--allow-http' }
  if ($TokenFile) { $enrollment += @('--token-file', $TokenFile); & $exe @enrollment }
  else {
    $secureToken = Read-Host 'One-time pairing key / 配对密钥' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    try {
      [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) | & $exe @enrollment
    } finally {
      [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
      $secureToken.Dispose()
    }
  }
  if ($LASTEXITCODE -ne 0) { throw 'Enrollment failed; the service was not installed.' }
  # LocalSystem reads ordinary local sources without per-folder LocalService grants.
  # Protect program and runtime data for SYSTEM and administrators, including custom data paths.
  & $exe install-tools --install-dir $InstallDir --data-dir $DataDir --mode windows
  if ($LASTEXITCODE -ne 0) { throw 'Unable to install management scripts.' }
  Set-AgentDirectoryAccess $InstallDir
  Set-AgentDirectoryAccess $DataDir
  $reported = & $exe version
  if ($LASTEXITCODE -ne 0 -or $reported -ne "$Version windows $arch") {
    throw 'Installed executable cannot run after permission setup. Registration and data are preserved.'
  }
  $command = '"' + $exe + '" run --data-dir "' + $DataDir + '"'
  New-Service -Name SyncSrvAgent -DisplayName 'SyncSrvAgent' -BinaryPathName $command -StartupType Automatic | Out-Null
  & sc.exe config SyncSrvAgent obj= 'LocalSystem'
  if ($LASTEXITCODE -ne 0) { throw 'Unable to set the service account.' }
  & sc.exe failure SyncSrvAgent reset= 86400 actions= restart/15000/restart/30000/restart/60000
  if ($LASTEXITCODE -ne 0) { throw 'Unable to set the service recovery policy.' }
  try {
    Start-Service SyncSrvAgent -ErrorAction Stop
    (Get-Service SyncSrvAgent).WaitForStatus('Running', [TimeSpan]::FromSeconds(20))
  } catch {
    # Service state can still report exit code 0 when process creation failed.
    # Preserve the original Win32 error; neither re-enroll nor remove runtime data.
    $startupError = $_
    Write-Warning 'Device registration is preserved. Do not delete the data directory or enroll again.'
    for ($cause = $startupError.Exception; $null -ne $cause; $cause = $cause.InnerException) {
      if ($cause -is [ComponentModel.Win32Exception]) {
        Write-Warning ("Windows service start error {0}: {1}" -f $cause.NativeErrorCode, $cause.Message)
      }
    }
    try {
      Get-CimInstance Win32_Service -Filter "Name='SyncSrvAgent'" |
        Format-List Name, State, StartName, PathName, ExitCode, ServiceSpecificExitCode | Out-Host
      $serviceLog = Join-Path $DataDir 'service.log'
      if (Test-Path -LiteralPath $serviceLog) { Get-Content -LiteralPath $serviceLog -Tail 50 | Out-Host }
      else { Write-Warning 'No service.log yet. The process may have failed before service logging started.' }
    } catch { Write-Warning 'Unable to collect additional service diagnostics.' }
    throw $startupError
  }
  Write-Host 'Service installed as LocalSystem. Authorize it in the center and configure your backup tasks. Ordinary local folders usually need no extra permission setup.'
  Remove-Item -LiteralPath $pendingFile
} finally {
  Remove-Item -LiteralPath $tempDir -Recurse -Force
}
