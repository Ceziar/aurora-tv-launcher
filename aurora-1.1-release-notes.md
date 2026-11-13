## Aurora 1.1

**Download:** `aurora-1.1-debug.apk` (about 0.5 MB)
**SHA-256:** `deffd107fe06cfbde8b9b14dfea1e130b35a1f9da49f5248f41bd5a40491b81e`

Built from the source at commit `cea1d4c` on `main`.

### What's new
- **Guided installer:** `tools/install-wizard.ps1` walks through the setup and stops at each manual step until it has checked that you did it. It saves a snapshot first, and `-Undo` puts things back.
- **Remote buttons that stay working:** on the TCL G10, TCL's own `TclAppBoot` refuses to start Aurora's accessibility service whenever Aurora's process is not running (log line: `callee_doesn't_have_OP_AUTO_START_permission`), so the remapped buttons silently died after a reinstall or a memory kill. `tools/restore-tv-config.ps1` now sets the `APP_AUTO_START` app-op to `allow`.
- **ServiceGuard (optional self-repair):** this TV also sometimes switches off Android's accessibility master switch. After a one-time `adb shell pm grant io.github.ceziar.aurora android.permission.WRITE_SECURE_SETTINGS` (the wizard offers it), Aurora turns the switch back on within a minute, at boot, and restarts the service if it is ticked but not running. It never ticks the service for you.
- **Aurora-tinted moon:** where a band of aurora crosses the moon, the moon takes on its colour. The dark side stays dark and the craters stay visible.
- **Snowmobile:** a single dark blob behind the headlight instead of a trail of dots.
- **Solid hills:** the ridge is now opaque, so the aurora stays behind the hills instead of glowing through them; only the very edge of the highest peaks is soft.
- **No aurora line under the hills:** the ridge now reaches down to the lake, so the thin line of aurora that showed under it is gone.
- **English and Finnish:** all text is in `res/values` (English) and `res/values-fi` (Finnish); the TV's system language picks. Add your own with `res/values-xx/strings.xml`.
- **README:** optional tweaks (animations off, TCL helper apps), and a warning that removing an app with a remote button makes that button open the app's Play Store page.

### Good to know
- Developed and tested on one TV only: TCL `G10_4K_GB_NF_32BIT`, Android 12, remote `BT_RC833A_B5`.
- ServiceGuard, the moon tint, the solid ridge and the autostart fix were verified on the author's TV in a build of the same code; the single-blob snowmobile, the no-line ridge and the English strings were not yet seen running on a TV; this exact APK file was compiled from the tagged source but was not itself installed.
- Signed with the standard Android *debug* key, so it is for sideloading, not for an app store.
- Setting it up disables two system packages (the stock launcher and the setup-recovery screen). Everything is reversible; see "Undo" in the README.
- Upgrading from 1.0: `adb install -r aurora-1.1-debug.apk`, then run `tools/restore-tv-config.ps1`, and tick the accessibility service again if it shows as off.
