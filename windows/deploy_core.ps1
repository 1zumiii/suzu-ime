# Deploys the new rime_llm.dll into Weasel WITHOUT any installer or dialog, with a backup and a one-command rollback.
#   deploy:    powershell -NoProfile -ExecutionPolicy Bypass -File deploy_core.ps1 -Dll <new rime_llm.dll>
#   rollback:  powershell -NoProfile -ExecutionPolicy Bypass -File deploy_core.ps1 -Rollback [-BackupDir <dir>]
# Only the DLL is replaced; the existing Lua scripts and the Rime user directory are left alone (the new core keeps their interface).
# Order: pre-checks -> backup -> ask WeaselServer to quit (weaselserver.exe /q, never a kill) -> copy -> start WeaselServer through an
# interactive scheduled task (so it lives in the desktop session, not in the caller's process tree) -> health check -> automatic rollback on failure.
param(
  [string]$Dll = "",
  [switch]$Rollback,
  [string]$BackupDir = "",
  [string]$WeaselDir = "D:\Applications\Rime\weasel-0.17.4",
  [string]$UserDir = "D:\AppData\Rime"
)
$ErrorActionPreference = "Stop"
$server = Join-Path $WeaselDir "WeaselServer.exe"
$live = Join-Path $WeaselDir "rime_llm.dll"
$taskName = "SuzuWeaselStart"
$root = Join-Path $UserDir "backup-core"
function Say($m) { Write-Host ("{0:HH:mm:ss} {1}" -f (Get-Date), $m) }
function Server-Running { [bool](Get-Process -Name WeaselServer -ErrorAction SilentlyContinue) }
function Stop-Server {
  if (-not (Server-Running)) { return }
  Say "asking WeaselServer to quit (/q)"
  & (Join-Path $WeaselDir "weaselserver.exe") /q | Out-Null
  for ($i = 0; $i -lt 40 -and (Server-Running); $i++) { Start-Sleep -Milliseconds 500 }
  if (Server-Running) { throw "WeaselServer did not quit within 20 s; nothing was changed" }
}
function Start-Server {
  # interactive scheduled task: runs in the logged-on user's desktop session and is independent of this process tree
  $act = New-ScheduledTaskAction -Execute $server -WorkingDirectory $WeaselDir
  $pri = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
  Register-ScheduledTask -TaskName $taskName -Action $act -Principal $pri -Force | Out-Null
  Start-ScheduledTask -TaskName $taskName
  for ($i = 0; $i -lt 60 -and -not (Server-Running); $i++) { Start-Sleep -Milliseconds 500 }
  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
  if (-not (Server-Running)) { throw "WeaselServer did not start" }
}
function Loaded-Hash {
  $p = Get-Process -Name WeaselServer -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $p) { return $null }
  $m = $p.Modules | Where-Object { $_.ModuleName -eq "rime_llm.dll" } | Select-Object -First 1
  if (-not $m) { return "not-loaded-yet" }
  return (Get-FileHash $m.FileName -Algorithm SHA256).Hash.Substring(0, 12)
}

if ($Rollback) {
  if (-not $BackupDir) { $BackupDir = (Get-ChildItem $root -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName }
  $old = Join-Path $BackupDir "rime_llm.dll"
  if (-not (Test-Path $old)) { throw "no backup DLL in $BackupDir" }
  Say "rollback from $BackupDir"
  Stop-Server
  Copy-Item $old $live -Force
  Start-Server
  Say ("rolled back; live DLL sha256 {0}, loaded {1}" -f (Get-FileHash $live).Hash.Substring(0, 12), (Loaded-Hash))
  exit 0
}

if (-not $Dll -or -not (Test-Path $Dll)) { throw "give the new DLL with -Dll" }
foreach ($f in "suzu.gguf", "suzu.vocab.txt", "lua\llm_filter.lua", "lua\llm_processor.lua") { if (-not (Test-Path (Join-Path $UserDir $f))) { throw "missing $f in $UserDir" } }
$newHash = (Get-FileHash $Dll -Algorithm SHA256).Hash.Substring(0, 12)
$oldHash = (Get-FileHash $live -Algorithm SHA256).Hash.Substring(0, 12)
if ($newHash -eq $oldHash) { Say "the live DLL already is the new one ($newHash); nothing to do"; exit 0 }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$bk = Join-Path $root $stamp
New-Item -ItemType Directory -Force $bk | Out-Null
Copy-Item $live (Join-Path $bk "rime_llm.dll")
foreach ($f in "lua\llm_filter.lua", "lua\llm_processor.lua", "rime_ice.custom.yaml", "suzu.name") { if (Test-Path (Join-Path $UserDir $f)) { Copy-Item (Join-Path $UserDir $f) (Join-Path $bk (Split-Path $f -Leaf)) } }
Set-Content (Join-Path $bk "MANIFEST.txt") "old dll sha256 prefix $oldHash`nnew dll sha256 prefix $newHash`nrollback: powershell -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Rollback -BackupDir `"$bk`"" -Encoding ascii
Say "backup written to $bk (old $oldHash, new $newHash)"

try {
  Stop-Server
  Copy-Item $Dll $live -Force
  if ((Get-FileHash $live).Hash.Substring(0, 12) -ne $newHash) { throw "copied DLL does not match" }
  Start-Server
  Start-Sleep -Seconds 3
  Say ("WeaselServer is running, loaded rime_llm.dll sha256 prefix: {0}" -f (Loaded-Hash))
} catch {
  Say ("FAILED: {0} -- rolling back" -f $_.Exception.Message)
  try { Stop-Server } catch { }
  Copy-Item (Join-Path $bk "rime_llm.dll") $live -Force
  try { Start-Server } catch { Say "could not restart WeaselServer; it will start on the next use of the input method, or run start_service.bat" }
  exit 1
}
Say "deployed. rollback command: powershell -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Rollback -BackupDir `"$bk`""
