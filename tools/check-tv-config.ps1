<#
.SYNOPSIS
  Read-only check of the Aurora TV setup. Changes nothing on the TV.

.DESCRIPTION
  Reports whether Aurora is installed, where the Home key would go, whether Google TV Home is still disabled,
  whether the screensaver is off, and whether the remote-button service is on and running.
  Exit code 0 = all good, 1 = TV not reachable, 2 = something needs the restore script.

  Handy after a reboot or a TCL update:
    powershell -ExecutionPolicy Bypass -File tools\check-tv-config.ps1
  If it reports a problem, fix it with tools\restore-tv-config.ps1.
#>
param(
    [Parameter(Mandatory = $true)][string]$TvAddress,
    [string]$Adb = 'adb'
)

$Package = 'io.github.ceziar.aurora'
$StockLauncher = 'com.google.android.apps.tv.launcherx'
$SetupRecovery = 'com.google.android.tungsten.setupwraith'
$Service = "$Package/$Package.RemoteKeyService"
$problems = 0

function Tv { & $Adb -s $TvAddress shell @args }
function Good($text) { Write-Host "   ok  : $text" -ForegroundColor Green }
function Bad($text) { Write-Host "   FIX : $text" -ForegroundColor Yellow; $script:problems++ }

if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) { throw "adb not found (put platform-tools on your PATH, or pass -Adb <path to adb.exe>)" }
& $Adb connect $TvAddress | Out-Null
$state = (& $Adb -s $TvAddress get-state 2>&1) -join ''
if ($state -ne 'device') {
    Write-Host "TV not reachable ($state). Is it on, and is Network debugging switched on in Developer options?" -ForegroundColor Red
    exit 1
}

if ((Tv pm list packages $Package) -match $Package) { Good 'Aurora is installed' } else { Bad 'Aurora is not installed' }

$homeOut = @(Tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME)
$homeTarget = if ($homeOut.Count -gt 0) { ($homeOut | Select-Object -Last 1).ToString().Trim() } else { '(unknown)' }
if ($homeTarget -match [regex]::Escape($Package)) { Good "Home goes to $homeTarget" } else { Bad "Home goes to $homeTarget, not Aurora" }

if ((Tv pm list packages -d) -match $StockLauncher) { Good 'Google TV Home is disabled' } else { Bad 'Google TV Home is enabled again' }

if ((Tv pm list packages -d) -match $SetupRecovery) { Good 'setup-recovery screen is disabled' } else { Bad 'setup-recovery screen is enabled again' }

$saver = ((Tv settings get secure screensaver_enabled) -join '').Trim()
if ($saver -eq '0') { Good 'screensaver is off' } else { Bad "screensaver_enabled = $saver" }

$enabled = (Tv settings get secure enabled_accessibility_services) -join ''
if ($enabled -like "*$Service*") { Good 'remote-button service is switched on' }
else { Bad 'remote-button service is OFF: TV Settings > Device Preferences > Accessibility > Aurora remote keys' }

$bound = @(Tv dumpsys accessibility) | Select-String 'Bound services' | Select-Object -First 1
if ($bound -and $bound.Line -match 'Aurora') { Good 'remote-button service is running' }
elseif ($enabled -like "*$Service*") { Bad 'remote-button service is ticked but not running (normal after a cold boot): run tools\restore-tv-config.ps1' }

Write-Host ('   info: security patch ' + ((Tv getprop ro.build.version.security_patch) -join '') + ', up ' + (((Tv uptime) -join '') -replace '^\s*\S+\s+up\s+','' -replace ',\s+\d+ users.*',''))
if ($problems -eq 0) { Write-Host "`nAll good." -ForegroundColor Green; exit 0 }
Write-Host "`n$problems thing(s) need attention. Run tools\restore-tv-config.ps1." -ForegroundColor Yellow
exit 2
