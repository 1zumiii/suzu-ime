function Resolve-WeaselPaths([string]$WeaselDir, [string]$UserDir) {
  if (-not $WeaselDir) {
    foreach ($key in "HKLM:\SOFTWARE\Rime\Weasel", "HKLM:\SOFTWARE\WOW6432Node\Rime\Weasel") {
      $install = (Get-ItemProperty -LiteralPath $key -Name WeaselRoot -ErrorAction SilentlyContinue).WeaselRoot
      if ($install -and (Test-Path -LiteralPath (Join-Path $install "WeaselServer.exe") -PathType Leaf)) {
        $WeaselDir = $install
        break
      }
    }
  }
  if (-not $WeaselDir -or -not (Test-Path -LiteralPath (Join-Path $WeaselDir "WeaselServer.exe") -PathType Leaf)) {
    throw "WeaselServer.exe not found. Pass the Weasel installation directory with -WeaselDir."
  }

  if (-not $UserDir) {
    $UserDir = (Get-ItemProperty -LiteralPath "HKCU:\SOFTWARE\Rime\Weasel" -Name RimeUserDir -ErrorAction SilentlyContinue).RimeUserDir
    if (-not $UserDir) { $UserDir = Join-Path $env:APPDATA "Rime" }
  }
  if (-not (Test-Path -LiteralPath $UserDir -PathType Container)) {
    throw "Rime user directory '$UserDir' not found. Pass its path with -UserDir."
  }

  @{
    WeaselDir = (Resolve-Path -LiteralPath $WeaselDir).ProviderPath
    UserDir = (Resolve-Path -LiteralPath $UserDir).ProviderPath
  }
}
