# Drives ctx_event_probe.lua against an Edge --app window holding test text in a textarea (no user data): checks that the event-driven
# context read  1) reads at once on an empty snapshot, 2) does NOT pick up a change while no event reaches it (no periodic read),
# 3) re-reads after the 10 s snapshot lifetime.  Prints source and Chinese character counts only.
# Foreground switch as in autotest\ctx_probe.ps1 (Alt tap + AttachThreadInput); every key is sent only after checking that the
# test window is in the foreground, otherwise the script stops (keys must never land in another window).
# Usage: powershell -STA -File ctx_event_probe.ps1 -SoDir <dir with rime_llm.so and lua54.exe>
param([Parameter(Mandatory = $true)][string]$SoDir,
      [string]$Edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
      [string]$Profile = (Join-Path $env:TEMP 'suzu_probe_edge_profile'),
      [switch]$Diag)                        # also read every 0.8 s for about 10 s before changing the text
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class Fg {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public static void Force(IntPtr h) {
    uint pid; uint fgT = GetWindowThreadProcessId(GetForegroundWindow(), out pid); uint me = GetCurrentThreadId();
    keybd_event(0x12, 0, 0, UIntPtr.Zero); keybd_event(0x12, 0, 2, UIntPtr.Zero);
    if (fgT != me) AttachThreadInput(me, fgT, true);
    BringWindowToTop(h); SetForegroundWindow(h);
    if (fgT != me) AttachThreadInput(me, fgT, false);
  }
}
"@
$dir = Join-Path $env:TEMP "suzu_ctx_probe"; New-Item -ItemType Directory -Force $dir | Out-Null
$html = "<!doctype html><meta charset=utf-8><title>SUZUPROBE</title><body style='margin:0'><textarea id=t style='width:100%;height:100vh;font-size:30px;border:0;outline:0'>今天我们一起去公园</textarea><script>var t=document.getElementById('t');t.focus();t.setSelectionRange(t.value.length,t.value.length);</script>"
$f = Join-Path $dir "probe.html"; [IO.File]::WriteAllText($f, $html, (New-Object Text.UTF8Encoding($false)))
Start-Process -FilePath $Edge -ArgumentList @("--user-data-dir=$Profile", '--no-first-run', '--inprivate', '--disable-extensions', ("--app=file:///" + $f.Replace('\', '/')), '--window-size=700,300', '--window-position=60,60')
$h = [IntPtr]::Zero; $ep = $null
for ($i = 0; $i -lt 60 -and $h -eq [IntPtr]::Zero; $i++) {
  Start-Sleep -Milliseconds 250
  $ep = Get-Process msedge -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*SUZUPROBE*" } | Select-Object -First 1
  if ($ep) { $h = $ep.MainWindowHandle }
}
if ($h -eq [IntPtr]::Zero) { "test window not found"; exit 1 }
[Fg]::Force($h); Start-Sleep -Milliseconds 800
function Guard { if ([Fg]::GetForegroundWindow() -ne $h) { "test window lost the foreground: stopping without sending keys"; if ($ep) { [void]$ep.CloseMainWindow() }; exit 1 } }
Guard
$psi = New-Object Diagnostics.ProcessStartInfo (Join-Path $SoDir "lua54.exe"), ("`"" + (Join-Path $PSScriptRoot "ctx_event_probe.lua") + "`" `"$SoDir`"")
$psi.UseShellExecute = $false; $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.CreateNoWindow = $true
$psi.StandardOutputEncoding = [Text.Encoding]::UTF8
$p = [Diagnostics.Process]::Start($psi)
Start-Sleep -Milliseconds 800
function Ask($tag) { $p.StandardInput.WriteLine($tag); $p.StandardInput.Flush(); Start-Sleep -Milliseconds 300 }
if ($Diag) { for ($i = 1; $i -le 12; $i++) { Ask ("t+{0:N1} s" -f (1.1 + 0.8 * $i)); Start-Sleep -Milliseconds 500 } }
# change the text while the probe receives no event (the paste goes through the real input method, not the probe's process)
Set-Clipboard -Value "散步很开心"
Guard; [System.Windows.Forms.SendKeys]::SendWait("^v")
Start-Sleep -Seconds 2
Ask "changed, +2 s (expect old)"
Start-Sleep -Seconds 9
Ask "changed, +11 s (expired)"
Ask "changed, +11 s +0.3 (expect new)"
$p.StandardInput.WriteLine("quit"); $p.StandardInput.Flush()
$out = $p.StandardOutput.ReadToEnd(); $p.WaitForExit(5000) | Out-Null
$out
if ($ep) { [void]$ep.CloseMainWindow() }
Remove-Item $dir -Recurse -ErrorAction SilentlyContinue
