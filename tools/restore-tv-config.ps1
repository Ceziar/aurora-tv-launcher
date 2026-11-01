<#
.SYNOPSIS
  Re-applies the Aurora TV setup after a TCL update, factory reset or reboot may have undone it.

.DESCRIPTION
  Every step only sets a value that is meant to stay set, so the script is safe to run at any time.
  It does NOT turn the accessibility service on (that is a security setting you switch on yourself);
  it only reports whether it is on.

  What it restores:
    1. Installs the Aurora APK if the app is missing.
    2. Disables Google TV Home (the stock launcher) so Aurora is the only home screen.
    3. Makes Aurora the system's home app.
    4. Turns the screensaver off.
    5. Checks the remote-button service, then tests that the Home key lands on Aurora.

  To undo it all, run the three commands in README-restore.txt (or: pm enable the stock launcher).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\restore-tv-config.ps1
  powershell -ExecutionPolicy Bypass -File tools\restore-tv-config.ps1 -TvAddress 192.168.1.50:5555
#>
param(
    [Parameter(Mandatory = $true)][string]$TvAddress,
    [string]$Adb = 'adb',
    [string]$Apk = (Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk')
)

$Package = 'io.github.ceziar.aurora'
$StockLauncher = 'com.google.android.apps.tv.launcherx'
$SetupRecovery = 'com.google.android.tungsten.setupwraith'
$Service = "$Package/$Package.RemoteKeyService"

function Tv { & $Adb -s $TvAddress shell @args }
function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Ok($text) { Write-Host "   ok: $text" -ForegroundColor Green }
function Warn($text) { Write-Host "   !! $text" -ForegroundColor Yellow }

if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) { throw "adb not found (put platform-tools on your PATH, or pass -Adb <path to adb.exe>)" }

Step "Connecting to $TvAddress"
& $Adb connect $TvAddress | Out-Host
$state = (& $Adb -s $TvAddress get-state 2>&1) -join ''
if ($state -ne 'device') {
    Warn "The TV answered '$state'. Check that the TV is on, Network debugging is enabled in Developer options,"
    Warn "and accept the 'Allow debugging' prompt on the TV (tick 'Always allow'). Then run this script again."
    exit 1
}
Ok 'connected'

Step 'Aurora app'
$installed = (Tv pm list packages $Package) -match $Package
if ($installed) {
    Ok 'already installed'
} elseif (Test-Path $Apk) {
    & $Adb -s $TvAddress install -r $Apk | Out-Host
} else {
    Warn "Aurora is not installed and no APK was found at $Apk. Build it first (gradle assembleDebug)."
    exit 1
}

# The order matters: the stock launcher must be enabled while Aurora is made the home app, and only then
# disabled. Doing it the other way round leaves the Home key pointing at an invisible recovery screen.
Step 'Make Aurora the home app'
# The role is handed back first so that setting it again rewrites everything; reinstalling the app wipes the
# "preferred home" setting and a plain re-add of a role Aurora already holds does nothing.
Tv pm enable $StockLauncher | Out-Null
Tv cmd role remove-role-holder --user 0 android.app.role.HOME $Package 0 | Out-Null
Tv cmd package set-home-activity --user 0 "$Package/.MainActivity" | Out-Host
Tv cmd role add-role-holder --user 0 android.app.role.HOME $Package 0 | Out-Host

Step 'Disable Google TV Home (stock launcher)'
Tv pm disable-user --user 0 $StockLauncher | Out-Host
Start-Sleep -Seconds 2

Step 'Disable the setup-recovery screen (it kept winning as the Home target)'
Tv pm disable-user --user 0 $SetupRecovery | Out-Host

Step 'Screensaver off'
Tv settings put secure screensaver_enabled 0
Tv settings put secure screensaver_activate_on_sleep 0
Tv settings put secure screensaver_activate_on_dock 0
Ok ('screensaver_enabled = ' + (Tv settings get secure screensaver_enabled))

Step 'Remote-button service'
# The service is only restarted if YOU already switched it on in the TV's Accessibility settings. After a cold
# boot the TV resets Android's accessibility master switch, so the service is ticked but never starts; this
# turns the master switch back on and does the same off-and-on the Settings screen does.
$enabled = ((Tv settings get secure enabled_accessibility_services) -join '').Trim()
if ($enabled -notlike "*$Service*") {
    Warn 'Aurora remote keys are OFF. Turn them on yourself:'
    Warn 'TV Settings > Device Preferences > Accessibility > Aurora remote keys > On.'
} else {
    $running = (@(Tv dumpsys accessibility) | Select-String 'Bound services' | Select-Object -First 1)
    if ($running -and $running.Line -match 'Aurora') {
        Ok 'Aurora remote keys: on and running'
    } elseif ($enabled -eq $Service) {
        Tv settings delete secure enabled_accessibility_services | Out-Null
        Start-Sleep -Seconds 2
        Tv settings put secure enabled_accessibility_services $Service
        Tv settings put secure accessibility_enabled 1
        Start-Sleep -Seconds 4
        $running = (@(Tv dumpsys accessibility) | Select-String 'Bound services' | Select-Object -First 1)
        if ($running -and $running.Line -match 'Aurora') { Ok 'Aurora remote keys: restarted' }
        else { Warn 'The service did not start. Switch it off and on in TV Settings > Accessibility.' }
    } else {
        Warn 'Other accessibility services are enabled too, so I left the list alone.'
        Warn 'Switch Aurora remote keys off and on in TV Settings > Accessibility.'
    }
}
Step 'Home key check'
# Ask the system where Home goes, instead of opening apps on the screen (someone may be watching TV).
$homeTarget = '(unknown)'
foreach ($try in 1..6) {
    $r = @(Tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME)
    if ($r.Count -gt 0) { $homeTarget = ($r | Select-Object -Last 1).ToString().Trim() }
    if ($homeTarget -match [regex]::Escape($Package)) { break }
    Start-Sleep -Seconds 2
}
if ($homeTarget -match [regex]::Escape($Package)) {
    Ok "Home goes to $homeTarget"
} else {
    Warn "Home would go to $homeTarget, not Aurora."
    Warn 'Re-enabling the stock launcher so the TV is not left without a home screen.'
    Tv pm enable $StockLauncher | Out-Host
    exit 2
}
Step 'Summary'
Write-Host ('   security patch : ' + (Tv getprop ro.build.version.security_patch))
Write-Host ('   build          : ' + (Tv getprop ro.build.fingerprint))
Write-Host ('   stock launcher : ' + $(if ((Tv pm list packages -d) -match $StockLauncher) { 'disabled' } else { 'ENABLED' }))
