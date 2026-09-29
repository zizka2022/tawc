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
- Not at boot: nothing runs until TAWC is opened.
