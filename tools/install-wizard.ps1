<#
.SYNOPSIS
  Guided installer for Aurora. Stops at every manual step and checks it before moving on.

.DESCRIPTION
  Steps: connect to the TV -> check the model -> install the APK -> make Aurora the home app ->
  tick the accessibility service -> optional tweaks. Nothing is changed until you have said yes, a snapshot of the
  settings it may change is saved first, and -Undo puts them back.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\install-wizard.ps1
  powershell -ExecutionPolicy Bypass -File tools\install-wizard.ps1 -Undo
#>
param(
    [string]$TvAddress = '',
    [string]$Adb = 'adb',
    [string]$Apk = '',
    [switch]$Undo
)

$Package = 'io.github.ceziar.aurora'
$Service = "$Package/$Package.RemoteKeyService"
$SnapshotFile = Join-Path $PSScriptRoot 'aurora-snapshot.json'
$Here = $PSScriptRoot

# TCL helper apps that only matter if you do not use them. Shown with their reason; every one is asked separately.
# Apps with a button on the remote (Netflix, Prime Video, Disney+, YouTube) are NOT in this list on purpose:
# removing one makes its button open that app's Play Store page.
$Bloat = @(
    @{ Pkg = 'com.tcl.esticker';           Why = 'sticker/ad app' },
    @{ Pkg = 'com.tcl.magiconnectfree';    Why = 'TCL phone-connect helper' },
    @{ Pkg = 'com.tcl.tv.tclhome_passive'; Why = 'TCL home helper' },
    @{ Pkg = 'com.tcl.exhibit';            Why = 'shop/demo mode app' },
    @{ Pkg = 'com.tcl.smartalexa';         Why = 'Alexa helper' },
    @{ Pkg = 'com.tcl.t_solo';             Why = 'TCL helper app' },
    @{ Pkg = 'com.tcl.waterfall.overseas'; Why = 'TCL content feed' }
)

function Tv { & $Adb -s $TvAddress shell @args }
function Head($t) { Write-Host "`n=== $t" -ForegroundColor Cyan }
function Ok($t)   { Write-Host "   ok: $t" -ForegroundColor Green }
function Warn($t) { Write-Host "   !! $t" -ForegroundColor Yellow }
function Ask($q, [bool]$default = $false) {
    $hint = if ($default) { 'Y/n' } else { 'y/N' }
    $a = (Read-Host "   $q [$hint]").Trim().ToLower()
    if ($a -eq '') { return $default }
    return $a.StartsWith('y')
}
# Waits until $Check returns true. Enter re-checks now, q quits.
function Gate($what, [scriptblock]$Check) {
    while ($true) {
        if (& $Check) { Ok $what; return }
        Warn "Waiting: $what"
        $k = Read-Host '   Do it, then press Enter to check again (or type q to quit)'
        if ($k.Trim().ToLower() -eq 'q') { Write-Host 'Stopped. Nothing further was changed.'; exit 1 }
    }
}

if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) {
    throw "adb not found. Install Android platform-tools and put it on PATH, or pass -Adb <path to adb.exe>."
}

Head 'Aurora guided installer'
if (-not $TvAddress) { $TvAddress = (Read-Host '   TV address, for example 192.168.1.50 (Settings > Network shows it)').Trim() }
if ($TvAddress -notmatch ':\d+$') { $TvAddress += ':5555' }

# ---------------------------------------------------------------- Undo
if ($Undo) {
    Head 'Undo'
    & $Adb connect $TvAddress | Out-Null
    if (-not (Test-Path $SnapshotFile)) { Warn "No snapshot found at $SnapshotFile, doing only the basic undo." ; $snap = $null }
    else { $snap = Get-Content $SnapshotFile -Raw | ConvertFrom-Json }
    Tv pm enable com.google.android.apps.tv.launcherx | Out-Host
    Tv pm enable com.google.android.tungsten.setupwraith | Out-Host
    Tv cmd role remove-role-holder --user 0 android.app.role.HOME $Package | Out-Host
    if ($snap) {
        Tv settings put secure screensaver_enabled $snap.screensaver
        foreach ($p in @($snap.wizardDisabled)) { if ($p) { Tv pm enable $p | Out-Host } }
        if ($snap.animationsChanged) {
            Tv settings put global window_animation_scale $snap.anim.window
            Tv settings put global transition_animation_scale $snap.anim.transition
            Tv settings put global animator_duration_scale $snap.anim.animator
        }
    } else { Tv settings put secure screensaver_enabled 1 }
    Warn 'Now switch the Aurora accessibility service off in TV Settings > Device Preferences > Accessibility.'
    Ok 'Undo done. The stock launcher is back.'
    exit 0
}

# ---------------------------------------------------------------- 1 Connect
Head 'Step 1 of 5: connect to the TV'
Write-Host '   On the TV: Settings > Device Preferences > About > press "Build" 7 times.'
Write-Host '   Then Settings > Device Preferences > Developer options > Network debugging: On.'
Gate "TV answers at $TvAddress" {
    & $Adb connect $TvAddress | Out-Null
    ((& $Adb -s $TvAddress get-state 2>&1) -join '') -eq 'device'
}
Write-Host '   If the TV shows an "Allow debugging?" prompt, tick "Always allow" and accept (the gate above waits for it).'

# ---------------------------------------------------------------- 2 Model
Head 'Step 2 of 5: check the TV model'
$name  = ((Tv getprop ro.product.name) -join '').Trim()
$model = ((Tv getprop ro.product.model) -join '').Trim()
$abi   = ((Tv getprop ro.product.cpu.abi) -join '').Trim()
Write-Host "   product=$name model=$model cpu=$abi"
if ($name -notmatch 'G10') {
    Warn 'Aurora was only tested on the TCL G10 (G10_4K_GB_NF_32BIT). Your remote key codes and package names may differ.'
    if (-not (Ask 'Continue anyway? Everything here is reversible with -Undo.')) { exit 1 }
} else { Ok 'TCL G10 recognised' }

# Snapshot of everything this wizard may change
$snap = [ordered]@{
    saved             = (Get-Date).ToString('s')
    screensaver       = ((Tv settings get secure screensaver_enabled) -join '').Trim()
    anim              = [ordered]@{
        window     = ((Tv settings get global window_animation_scale) -join '').Trim()
        transition = ((Tv settings get global transition_animation_scale) -join '').Trim()
        animator   = ((Tv settings get global animator_duration_scale) -join '').Trim()
    }
    animationsChanged = $false
    wizardDisabled    = @()
}
$snap | ConvertTo-Json -Depth 4 | Set-Content -Path $SnapshotFile -Encoding ASCII
Ok "Snapshot of current settings saved to $SnapshotFile"

# ---------------------------------------------------------------- 3 Install
Head 'Step 3 of 5: install the Aurora app'
if (-not $Apk) {
    foreach ($c in @((Join-Path $Here '..\app\build\outputs\apk\debug\app-debug.apk'), (Join-Path $Here '..\aurora-1.0-debug.apk'), (Join-Path (Get-Location) 'aurora-1.0-debug.apk'))) {
        if (Test-Path $c) { $Apk = (Resolve-Path $c).Path; break }
    }
}
Write-Host '   You need the APK: download it from the repository Releases page, or build it with .\gradlew.bat assembleDebug.'
Gate 'the APK file exists' {
    if ($Apk -and (Test-Path $Apk)) { return $true }
    $p = (Read-Host '   Path to the APK (Enter to keep waiting)').Trim('"', ' ')
    if ($p -and (Test-Path $p)) { $script:Apk = (Resolve-Path $p).Path; return $true }
    return $false
}
Write-Host ('   SHA-256: ' + (Get-FileHash $Apk -Algorithm SHA256).Hash.ToLower())
Write-Host '   Compare it with the hash in the release notes if you downloaded the APK.'
if (-not (Ask 'Install Aurora on the TV now?' $true)) { exit 1 }
& $Adb -s $TvAddress install -r $Apk | Out-Host

# ---------------------------------------------------------------- 4 Home
Head 'Step 4 of 5: make Aurora the home screen'
Write-Host '   This disables Google TV Home and the setup-recovery package, and turns the screensaver off.'
Write-Host '   If Home would not land on Aurora, the script enables the stock launcher again.'
if (-not (Ask 'Switch the home screen to Aurora now?' $true)) { exit 1 }
& (Join-Path $Here 'restore-tv-config.ps1') -TvAddress $TvAddress -Adb $Adb -Apk $Apk
if ($LASTEXITCODE -ne 0) { Warn "The setup script reported a problem (exit $LASTEXITCODE). Fix that before going on."; exit $LASTEXITCODE }

# ---------------------------------------------------------------- 5 Accessibility
Head 'Step 5 of 5: remote buttons (accessibility service)'
Write-Host '   On the TV: Settings > Device Preferences > Accessibility > Aurora remote keys > On.'
Write-Host '   It is a security setting, so only you can switch it on.'
Gate 'the Aurora service is ticked' {
    ((Tv settings get secure enabled_accessibility_services) -join '') -like "*$Service*"
}
& (Join-Path $Here 'restore-tv-config.ps1') -TvAddress $TvAddress -Adb $Adb -Apk $Apk | Out-Null
Ok 'service checked and restarted if needed'

Write-Host '   This TV resets the accessibility master switch now and then, which silently stops the remote buttons.'
Write-Host '   Aurora can turn it back on by itself (it only does so if you ticked the service) once you grant it'
Write-Host '   the WRITE_SECURE_SETTINGS permission. The grant is a single adb command and is removed by uninstalling.'
if (Ask 'Grant WRITE_SECURE_SETTINGS so Aurora keeps its remote buttons running?' $true) {
    Tv pm grant $Package android.permission.WRITE_SECURE_SETTINGS | Out-Host
    Tv dumpsys deviceidle whitelist "+$Package" | Out-Host
    Ok 'granted; Aurora re-enables the accessibility switch within a minute if the TV turns it off'
}

# ---------------------------------------------------------------- Optional tweaks
Head 'Optional tweaks (all off by default, all reversible with -Undo)'
if (Ask 'Turn off the TV menu animations (window, transition, animator = 0)? Makes menus snappier.') {
    Tv settings put global window_animation_scale 0
    Tv settings put global transition_animation_scale 0
    Tv settings put global animator_duration_scale 0
    $snap.animationsChanged = $true
    Ok 'animations off'
}
Write-Host '   TCL helper apps. Disabling one hides it and stops it running; its data stays.'
Write-Host '   Do NOT remove an app that has a button on your remote: that button then opens its Play Store page.'
foreach ($b in $Bloat) {
    if (-not ((Tv pm list packages $b.Pkg) -match [regex]::Escape($b.Pkg))) { continue }
    if (Ask "Disable $($b.Pkg) ($($b.Why))?") {
        Tv pm disable-user --user 0 $b.Pkg | Out-Host
        $snap.wizardDisabled += $b.Pkg
    }
}
$snap | ConvertTo-Json -Depth 4 | Set-Content -Path $SnapshotFile -Encoding ASCII

Head 'Done'
Write-Host '   Press Home on the remote: you should land on Aurora. Run tools\check-tv-config.ps1 any time to re-check.'
Write-Host '   To undo everything:  tools\install-wizard.ps1 -Undo'
