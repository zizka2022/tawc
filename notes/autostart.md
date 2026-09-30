# Rootfs autostart

Executables in a rootfs's `/root/.config/tawc/autostart/` run once per
app process: after the first activity starts and after
`TawcApplication`'s startup chores (so files refreshed by
`TawcInstaller.installAll` are in place). Code:
`install/RootfsAutostart.kt`.

- Every READY install, entries in name order. Regular executable files or
  symlinks (the shell resolves rootfs targets); names must match
  `[A-Za-z0-9][A-Za-z0-9._-]*`, so they are safe unquoted and double as
  the notification label.
- Each runs through `UserRootfsSession.startInside`: a `Command` hold
  while it lives, and output appended to `/tmp/tawc-autostart.log`.
  Entries should start their daemon (`setsid`) and exit; the daemon is
  then held by the session service's stray tail.
- Why wait for an activity: the hold starts the foreground service,
  which Android 12+ refuses from the background. A process started only
  by the debug broker never autostarts.
- Not XDG autostart on purpose: `~/.config/autostart` / `/etc/xdg/autostart`
  are desktop-session items (e.g. at-spi) that TAWC should not run on
  app start.
- At boot only by opt-in: an install whose rootfs has
  `/root/.config/tawc/autostart-at-boot` also runs its entries on
  BOOT_COMPLETED (`install/BootAutostartReceiver.kt`), and on
  MY_PACKAGE_REPLACED, since an update of TAWC stops the rootfs just as a
  reboot does. Both let a background app start its foreground service. The receiver takes a hold
  at once, so the service is up inside that allowance; it is released
  after the entries have started. Each install runs at most once per
  process, so opening TAWC later doesn't run it again. Other installs
  still wait for an activity.
