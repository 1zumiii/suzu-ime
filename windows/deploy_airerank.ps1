# Switches Weasel from the patched upstream llm_processor/llm_filter to the shared ai_rerank_* Lua (same as Mac and Android),
# with no installer and no dialog, a backup, and a one-command rollback. The old files stay on disk (they are only unused).
#   install:   powershell -NoProfile -ExecutionPolicy Bypass -File deploy_airerank.ps1 [-Ranker legacy|unified]
#   rollback:  powershell -NoProfile -ExecutionPolicy Bypass -File deploy_airerank.ps1 -Rollback [-BackupDir <dir>]
# Order: pre-checks -> backup -> quit WeaselServer (weaselserver.exe /q, never a kill) -> copy Lua -> rewrite the three names in
# rime_ice.custom.yaml -> start WeaselServer through an interactive scheduled task -> WeaselDeployer.exe /deploy while the server runs: the server
# recompiles the config by itself (about 3 minutes); the settings window the deployer shows is never clicked, it is closed after the wait
# -> wait until the compiled config has the new names -> health check -> automatic rollback on failure.
param(
  [switch]$Rollback,
  [switch]$SetRanker,                         # only change llm_rerank/ranker (to -Ranker) in the already switched config, then recompile
  [string]$BackupDir = "",
  [ValidateSet("legacy", "unified")][string]$Ranker = "legacy",
  [string]$LuaSrc = "",                       # suzu-ime rime\lua (shared files); default: ..\rime\lua next to this script
  [string]$WinLuaSrc = "",                    # suzu-ime windows\lua (Windows-only glue); default: .\lua next to this script
  [string]$WeaselDir = "D:\Applications\Rime\weasel-0.17.4",
  [string]$UserDir = "D:\AppData\Rime"
)
$ErrorActionPreference = "Stop"
if (-not $LuaSrc) { $LuaSrc = Join-Path (Split-Path $PSScriptRoot -Parent) "rime\lua" }
if (-not $WinLuaSrc) { $WinLuaSrc = Join-Path $PSScriptRoot "lua" }
$server = Join-Path $WeaselDir "WeaselServer.exe"
$deployer = Join-Path $WeaselDir "WeaselDeployer.exe"
$taskName = "SuzuWeaselStart"
$root = Join-Path $UserDir "backup-airerank"
$yaml = Join-Path $UserDir "rime_ice.custom.yaml"
$luaDir = Join-Path $UserDir "lua"
$shared = "ai_rerank_filter.lua", "ai_rerank_processor.lua", "input_log.lua", "rank_unified.lua", "rerank_json.lua",
          "personal_pairs.lua", "recent_boost_filter.lua", "corpus_archive.lua"
$winOnly = "ai_rerank_win_ctx.lua"
function Say($m) { Write-Host ("{0:HH:mm:ss} {1}" -f (Get-Date), $m) }
function Server-Running { [bool](Get-Process -Name WeaselServer -ErrorAction SilentlyContinue) }
function Stop-Server {
  if (-not (Server-Running)) { return }
  Say "asking WeaselServer to quit (/q)"
  & $server /q | Out-Null
  for ($i = 0; $i -lt 40 -and (Server-Running); $i++) { Start-Sleep -Milliseconds 500 }
  if (Server-Running) { throw "WeaselServer did not quit within 20 s; nothing was changed" }
}
function Start-Server {
  $act = New-ScheduledTaskAction -Execute $server -WorkingDirectory $WeaselDir
  $pri = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
  Register-ScheduledTask -TaskName $taskName -Action $act -Principal $pri -Force | Out-Null
  Start-ScheduledTask -TaskName $taskName
  for ($i = 0; $i -lt 60 -and -not (Server-Running); $i++) { Start-Sleep -Milliseconds 500 }
  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
  if (-not (Server-Running)) { throw "WeaselServer did not start" }
}
function Compiled-Has($text) {
  $f = Join-Path $UserDir "build\rime_ice.schema.yaml"
  (Test-Path $f) -and [bool](Select-String -Path $f -Pattern $text -SimpleMatch -Quiet)
}
function Recompile($marker) {
  # WeaselDeployer.exe /deploy asks the RUNNING server to recompile (about 2 minutes). The compiled yaml files are deleted first: without that Rime sees no change and compiles nothing.
  # The deployer shows a settings window that is never clicked: wait until the compiled config has the marker, then close the window.
  if (-not (Server-Running)) { Start-Server }
  Start-Sleep -Seconds 3
  foreach ($n in 'rime_ice.schema.yaml', 'default.yaml') { Remove-Item (Join-Path $UserDir ('build'+[char]92+$n)) -ErrorAction SilentlyContinue }
  $t0 = Get-Date
  $p = Start-Process $deployer -ArgumentList "/deploy" -WorkingDirectory $WeaselDir -PassThru
  $done = $false
  for ($i = 0; $i -lt 160 -and -not $done; $i++) {
    Start-Sleep -Seconds 3
    $f = Join-Path $UserDir "build\rime_ice.schema.yaml"
    $done = (Compiled-Has $marker) -and (Test-Path $f) -and ((Get-Item $f).LastWriteTime -gt $t0)
  }
  Start-Sleep -Seconds 5
  try { if (-not $p.HasExited) { [void]$p.CloseMainWindow(); Start-Sleep -Seconds 2; if (-not $p.HasExited) { $p.Kill() } } } catch {}
  if (-not $done) { throw "compiled config does not contain $marker after 8 min" }
}
function Restore($dir) {
  $list = Join-Path $dir "added.txt"
  if (Test-Path $list) { Get-Content $list | ForEach-Object { Remove-Item (Join-Path $luaDir $_) -ErrorAction SilentlyContinue } }
  foreach ($f in Get-ChildItem (Join-Path $dir "lua") -File -ErrorAction SilentlyContinue) { Copy-Item $f.FullName (Join-Path $luaDir $f.Name) -Force }
  Copy-Item (Join-Path $dir "rime_ice.custom.yaml") $yaml -Force
}

if ($Rollback) {
  if (-not $BackupDir) { $BackupDir = (Get-ChildItem $root -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName }
  if (-not (Test-Path (Join-Path $BackupDir "rime_ice.custom.yaml"))) { throw "no backup in $BackupDir" }
  Say "rollback from $BackupDir"
  Stop-Server
  Restore $BackupDir
  Recompile "llm_processor"
  Say "rolled back; compiled config uses llm_processor again"
  exit 0
}

if ($SetRanker) {
  $t = [IO.File]::ReadAllText($yaml, [Text.Encoding]::UTF8)
  if ($t.IndexOf('ai_rerank_win_ctx') -lt 0) { throw "config is not switched to ai_rerank yet; run without -SetRanker first" }
  $t = [regex]::Replace($t, '(?m)^[ 	]*ranker:[ 	]*\w+[ 	]*?
', '')
  if ($Ranker -eq "unified") { $t = [regex]::Replace($t, '(?m)^([ 	]*)max_candidates: 5[ 	]*?$', "`$0`r`n`$1ranker: unified", 1) }
  [IO.File]::WriteAllText($yaml, $t, (New-Object Text.UTF8Encoding($false)))
  Recompile "ai_rerank_processor"
  Say "ranker is now $Ranker (compiled: $(Compiled-Has 'ranker: unified'))"
  exit 0
}

# ---- pre-checks
foreach ($f in $shared) { if (-not (Test-Path (Join-Path $LuaSrc $f))) { throw "missing $LuaSrc\$f" } }
foreach ($f in $winOnly) { if (-not (Test-Path (Join-Path $WinLuaSrc $f))) { throw "missing $WinLuaSrc\$f" } }
$text = [IO.File]::ReadAllText($yaml, [Text.Encoding]::UTF8)
foreach ($need in 'lua_processor@*llm_processor', 'lua_filter@*llm_filter') { if ($text.IndexOf($need) -lt 0) { throw "rime_ice.custom.yaml does not contain $need (already switched?)" } }
foreach ($f in "suzu.gguf", "suzu.vocab.txt") { if (-not (Test-Path (Join-Path $UserDir $f))) { throw "missing $UserDir\$f" } }

# ---- backup
$bk = Join-Path $root (Get-Date -Format "yyyyMMdd-HHmmss")
New-Item -ItemType Directory -Force (Join-Path $bk "lua") | Out-Null
Copy-Item $yaml $bk
$added = @()
foreach ($f in ($shared + $winOnly)) {
  $dst = Join-Path $luaDir $f
  if (Test-Path $dst) { Copy-Item $dst (Join-Path $bk "lua") } else { $added += $f }
}
Set-Content -Path (Join-Path $bk "added.txt") -Value $added -Encoding ascii
Say "backup written to $bk"

# ---- switch (any failure rolls back)
try {
  Stop-Server
  foreach ($f in $shared) { Copy-Item (Join-Path $LuaSrc $f) (Join-Path $luaDir $f) -Force }
  foreach ($f in $winOnly) { Copy-Item (Join-Path $WinLuaSrc $f) (Join-Path $luaDir $f) -Force }
  $new = $text.Replace('lua_processor@*llm_processor', 'lua_processor@*ai_rerank_processor').Replace('lua_filter@*llm_filter', 'lua_filter@*ai_rerank_filter')
  # Windows-only glue right behind the shared processor (keys apply in dictionary order: @before 0 first, then @before 1)
  $new = $new.Replace('  "engine/filters/@after last":', '  "engine/processors/@before 1": lua_processor@*ai_rerank_win_ctx' + "`r`n" + '  "engine/filters/@after last":')
  if ($new.IndexOf('ai_rerank_win_ctx') -lt 0) { throw "could not insert the glue processor line" }
  if ($Ranker -eq "unified") { $new = $new -replace '(?m)^(\s*)max_candidates: 5\s*$', "`$0`r`n`$1ranker: unified" }
  [IO.File]::WriteAllText($yaml, $new, (New-Object Text.UTF8Encoding($false)))
  Recompile "ai_rerank_processor"
  Start-Sleep -Seconds 3
  if (-not (Server-Running)) { throw "WeaselServer is not running after start" }
  Say "switched. rollback: powershell -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Rollback -BackupDir `"$bk`""
} catch {
  Say "FAILED: $($_.Exception.Message) -- rolling back"
  try { Stop-Server } catch {}
  Restore $bk
  try { Recompile "llm_processor" } catch { Say "recompile during rollback: $($_.Exception.Message)"; try { if (-not (Server-Running)) { Start-Server } } catch { Say "could not start the server: $($_.Exception.Message)" } }
  exit 1
}
