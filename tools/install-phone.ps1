# Installs the built Questa APK(s) on the phone with adb. Called by build.bat.
# Connection order: (1) a Wi-Fi adb device that is already connected,
# (2) the last saved Wi-Fi IP (out\.adb_wifi, git-ignored), (3) USB.
# A USB-only phone is switched to Wi-Fi (adb tcpip 5555) and the IP is saved.
# Exit code: 0 = installed or skipped by the user, 1 = install failed.
# APK paths come from the env vars BUILT_DEV / BUILT_REL (set by build.bat), or the params.
param(
  [string]$DevApk = $env:BUILT_DEV,
  [string]$ReleaseApk = $env:BUILT_REL
)
$ErrorActionPreference = 'Continue'
$DevPkg = 'io.github.palo007.questa.dev'
$RelPkg = 'io.github.palo007.questa'
$Port = 5555
$OutDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'out'
$IpFile = Join-Path $OutDir '.adb_wifi'
$Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'

if (-not (Test-Path -LiteralPath $Adb)) {
  Write-Host "[ERROR] adb not found: $Adb"
  Write-Host '        Install "Android SDK Platform-Tools" in Android Studio (SDK Manager).'
  exit 1
}

# Runs adb, returns all output lines (stdout + stderr) as strings.
function Adb {
  $out = & $Adb @args 2>&1
  $script:AdbExit = $LASTEXITCODE
  return @($out | ForEach-Object { "$_" })
}

# Runs adb with a time limit (adb connect can hang on a wrong IP). Returns the output text.
function AdbTimed([int]$Seconds, [string[]]$AdbArgs) {
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $Adb
  $psi.Arguments = ($AdbArgs -join ' ')
  $psi.UseShellExecute = $false
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.CreateNoWindow = $true
  $p = [System.Diagnostics.Process]::Start($psi)
  $so = $p.StandardOutput.ReadToEndAsync()
  $se = $p.StandardError.ReadToEndAsync()
  if (-not $p.WaitForExit($Seconds * 1000)) {
    try { $p.Kill() } catch {}
    return 'timeout'
  }
  return ($so.Result + $se.Result)
}

function Ask([string]$Question, [bool]$DefaultYes) {
  $a = Read-Host $Question
  if ([string]::IsNullOrWhiteSpace($a)) { return $DefaultYes }
  return $a.Trim().ToLower().StartsWith('y')
}

function IsWifi([string]$Serial) {
  return ($Serial -match '^\d{1,3}(\.\d{1,3}){3}:\d+$') -or ($Serial -like '*_adb-tls-connect*')
}

# All devices from "adb devices": objects with Serial and State.
function Get-Devices {
  $list = @()
  foreach ($line in (Adb devices)) {
    if ($line -match '^(\S+)\s+(device|unauthorized|offline|authorizing)\s*$') {
      $list += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
    }
  }
  return $list
}

function Get-SavedIp {
  if (Test-Path -LiteralPath $IpFile) {
    $ip = (Get-Content -LiteralPath $IpFile -TotalCount 1)
    if ($ip) { return $ip.Trim() }
  }
  return ''
}

function Save-Ip([string]$Ip) {
  if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
  Set-Content -LiteralPath $IpFile -Value $Ip -Encoding Ascii
}

function Connect-Wifi([string]$Ip) {
  $r = AdbTimed 10 @('connect', "${Ip}:$Port")
  return ($r -match 'connected to') -and -not ($r -match 'cannot|failed|unable')
}

# Phone IP from "ip route" (wlan line first). Empty when the phone is not on Wi-Fi.
function Get-PhoneIp([string]$Serial) {
  $lines = Adb -s $Serial shell ip route
  $best = ''
  foreach ($l in $lines) {
    if ($l -match 'src\s+(\d{1,3}(\.\d{1,3}){3})') {
      $ip = $Matches[1]
      if ($l -match '\bdev\s+wlan') { return $ip }
      # Skip mobile data and VPN lines (ccmni, rmnet, tun, ...).
      if (-not $best -and $l -notmatch '\bdev\s+(ccmni|rmnet|tun|dummy|lo)') { $best = $ip }
    }
  }
  return $best
}

# Switches a USB phone to Wi-Fi adb. Returns the Wi-Fi serial, or the USB serial if it did not work.
function Switch-ToWifi([string]$UsbSerial) {
  $ip = Get-PhoneIp $UsbSerial
  if (-not $ip) {
    Write-Host '[INFO] The phone is not on Wi-Fi. Install over USB.'
    return $UsbSerial
  }
  Write-Host "Switching the phone to Wi-Fi adb ($ip`:$Port) ..."
  Adb -s $UsbSerial tcpip $Port | Out-Null
  for ($i = 0; $i -lt 5; $i++) {
    Start-Sleep -Seconds 2
    if (Connect-Wifi $ip) {
      Save-Ip $ip
      Write-Host "[OK] Wi-Fi adb connected. IP saved in out\.adb_wifi. Next time no cable is needed."
      return "${ip}:$Port"
    }
  }
  Write-Host '[WARN] Wi-Fi connect did not work (same Wi-Fi network as this PC?). Install over USB.'
  return $UsbSerial
}

function Get-Prop([string]$Serial, [string]$Name) {
  return ((Adb -s $Serial shell getprop $Name) -join '').Trim()
}

# Finds the phone to use. Returns its serial, or '' when the user skips.
function Select-Target {
  while ($true) {
    $devs = @(Get-Devices)
    $ready = @($devs | Where-Object { $_.State -eq 'device' })

    # (2) No Wi-Fi device yet: try the saved IP.
    if (-not ($ready | Where-Object { IsWifi $_.Serial })) {
      $saved = Get-SavedIp
      if ($saved) {
        Write-Host "Trying the saved Wi-Fi IP $saved ..."
        if (Connect-Wifi $saved) {
          Write-Host '[OK] Connected over Wi-Fi.'
          $devs = @(Get-Devices)
          $ready = @($devs | Where-Object { $_.State -eq 'device' })
        } else {
          Write-Host '[INFO] Not reachable.'
        }
      }
    }

    # One entry per phone (the same phone can be on USB and Wi-Fi). Prefer Wi-Fi.
    $phones = [ordered]@{}
    foreach ($d in $ready) {
      $id = Get-Prop $d.Serial 'ro.serialno'
      if (-not $id) { $id = $d.Serial }
      $wifi = IsWifi $d.Serial
      if (-not $phones.Contains($id)) {
        $phones[$id] = [pscustomobject]@{ Serial = $d.Serial; Wifi = $wifi; Model = (Get-Prop $d.Serial 'ro.product.model') }
      } elseif ($wifi -and -not $phones[$id].Wifi) {
        $phones[$id].Serial = $d.Serial
        $phones[$id].Wifi = $true
      }
    }
    $list = @($phones.Values)

    if ($list.Count -eq 0) {
      if ($devs | Where-Object { $_.State -eq 'unauthorized' }) {
        Write-Host '[INFO] The phone is connected but not allowed yet.'
        Write-Host '       Unlock the phone and tap "Allow" on the USB debugging prompt.'
      } else {
        Write-Host '[INFO] No phone found.'
        Write-Host '       Plug in the phone with a USB cable. USB debugging must be on'
        Write-Host '       (Settings > Developer options > USB debugging).'
      }
      if (Ask 'Retry? (Y = retry, n = skip install)' $true) { continue }
      return ''
    }

    $pick = $list[0]
    if ($list.Count -gt 1) {
      Write-Host 'More than one phone:'
      for ($i = 0; $i -lt $list.Count; $i++) {
        $how = if ($list[$i].Wifi) { 'Wi-Fi' } else { 'USB' }
        Write-Host ("  {0}  {1}  ({2}, {3})" -f ($i + 1), $list[$i].Model, $how, $list[$i].Serial)
      }
      $n = 0
      while ($n -lt 1 -or $n -gt $list.Count) {
        $a = Read-Host "Pick 1-$($list.Count) (0 = skip install)"
        if ($a -eq '0') { return '' }
        [int]::TryParse($a, [ref]$n) | Out-Null
      }
      $pick = $list[$n - 1]
    }

    # (3) USB only: switch to Wi-Fi.
    if (-not $pick.Wifi) { return (Switch-ToWifi $pick.Serial) }
    return $pick.Serial
  }
}

# Installs one APK. Returns $true when it is installed.
function Install-Apk([string]$Serial, [string]$Apk, [string]$Pkg) {
  Write-Host ''
  Write-Host "Installing $Apk ..."
  $out = (Adb -s $Serial install -r $Apk) -join "`n"
  if ($out -match 'Success') { Write-Host "[OK] $Pkg installed."; return $true }

  if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match|INSTALL_FAILED_SHARED_USER_INCOMPATIBLE') {
    Write-Host ''
    Write-Host "[PROBLEM] The phone already has $Pkg, signed with a DIFFERENT key."
    Write-Host '          Android updates an app only when the new APK has the same key.'
    Write-Host '          (For example: a release APK signed with another .jks, or the'
    Write-Host '          dev app built on another PC with another debug key.)'
    Write-Host '          The fix is to uninstall the old app first, then install.'
    Write-Host '          WARNING: uninstall DELETES the app data on the phone.'
    Write-Host '          Sync to Dropbox or export your data in the app first.'
    if (-not (Ask "Uninstall $Pkg now and install again? (y/N)" $false)) {
      Write-Host '[INFO] Not installed. The old app is kept.'
      return $false
    }
    Adb -s $Serial uninstall $Pkg | Out-Null
    $out = (Adb -s $Serial install $Apk) -join "`n"
    if ($out -match 'Success') { Write-Host "[OK] $Pkg installed (fresh)."; return $true }
  }
  Write-Host "[ERROR] Install failed:"
  Write-Host $out
  return $false
}

function Start-App([string]$Serial, [string]$Pkg) {
  Adb -s $Serial shell monkey -p $Pkg -c android.intent.category.LAUNCHER 1 | Out-Null
  if ($script:AdbExit -eq 0) { Write-Host "[OK] $Pkg started on the phone." }
  else { Write-Host "[WARN] Could not start $Pkg. Open it on the phone." }
}

# ---------------------------------------------------------------------------
$jobs = @()
if ($ReleaseApk) { $jobs += [pscustomobject]@{ Apk = $ReleaseApk; Pkg = $RelPkg } }
if ($DevApk)     { $jobs += [pscustomobject]@{ Apk = $DevApk; Pkg = $DevPkg } }
if ($jobs.Count -eq 0) { Write-Host '[INFO] Nothing to install.'; exit 0 }

Write-Host ''
Write-Host '=== Installing on the phone ==='
Adb start-server | Out-Null
$target = Select-Target
if (-not $target) { Write-Host '[INFO] Install skipped.'; exit 0 }
Write-Host "Phone: $target"

$failed = $false
$started = $false
foreach ($j in $jobs) {
  if (Install-Apk $target $j.Apk $j.Pkg) {
    # Start only the first installed app (release first when both were built).
    if (-not $started) { Start-App $target $j.Pkg; $started = $true }
  } else {
    $failed = $true
  }
}
if ($failed) { exit 1 }
exit 0
