# tx10-clock — linux (X11 kiosk)

Linux port of the clock display. `ClockRenderer` and the whole JVM core compile
**unmodified**: the `android.graphics` imports resolve to Java2D-backed shims
under `src/main/java/android/graphics/`, so the renderer stays single-source
across Android, tvOS, and Linux.

Shared sources come from `../app/src/main/java`; the Android-surface classes
(`MainActivity`, `ClockView`, `BootReceiver`, `AppState`, `ConfigDir`) are
excluded and replaced by this module's runtime.

## Build

```bash
./gradlew :linux:jar   # -> linux/build/libs/tx10-clock-linux.jar
```

Requires a full JDK (17+) to build; runs on JRE 17+.

## Run

Kiosk (fullscreen on the default screen, X11):

```bash
java -jar tx10-clock-linux.jar
```

Headless single-frame render (verification/golden lever, exact display path):

```bash
java -jar tx10-clock-linux.jar --shot frame.png 1920 1080 2026-07-12T22:09:42Z
```

Capture the running kiosk screen without external screenshot tooling:

```bash
DISPLAY=:0 java -jar tx10-clock-linux.jar --snap snap.png
```

## Configuration contract

Same file contract as the Android app: `config.json` / `status.json` in the
config directory. Config directory resolution:

- `TX10CLOCK_CONFIG_DIR` env var, else
- `~/.tx10-clock` (created on start).

The file is polled once per tick; `reload()` + last-known-good semantics come
from the shared `ConfigStore`. `status.json` mirrors the verifier contract via
`ConfigStore.writeStatus`.

## Fonts

The `android.graphics.Typeface` shim maps `sans-serif`, `sans-serif-light`,
`sans-serif-medium` to Roboto TTFs. Font lookup:

- `TX10CLOCK_FONT_DIR` (directory with `Roboto-*.ttf`), else
- scan of `/usr/share/fonts` (install `font-roboto` on Alpine).

Missing weights fall back to Roboto Regular, then to the JDK logical sans font.

## Deploy to a NUC-style kiosk host

```bash
./scripts/deploy-nuc.sh [user@host]        # default: $TX10CLOCK_SSH, else root@nuc
```

If the target is only reachable via a jump host, set `TX10CLOCK_SSH_HOP=user@hop`.

Reboot persistence on a fresh Alpine host: run X and the jar under OpenRC's
`supervise-daemon` (both auto-restart, both in the default runlevel):

`/etc/init.d/tx10-clock-x`:

```sh
#!/sbin/openrc-run
supervisor="supervise-daemon"
name="tx10-clock X server"
command="/usr/bin/Xorg"
# -s 0 -dpms: never blank — this is a display, idle timers must stay off
command_args=":0 -sharevts -noreset -s 0 -dpms"
pidfile="/run/tx10-clock-x.pid"
output_log="/tmp/xorg-sup.log"
error_log="/tmp/xorg-sup.err"

depend() {
	after local
}
```

`/etc/init.d/tx10-clock`:

```sh
#!/sbin/openrc-run
supervisor="supervise-daemon"
name="tx10-clock kiosk"
command="/usr/bin/java"
command_args="-jar /root/tx10-clock-linux.jar"
pidfile="/run/tx10-clock.pid"
output_log="/tmp/clock.log"
error_log="/tmp/clock.err"
export DISPLAY=:0

depend() {
	need tx10-clock-x
}
```

Then `rc-update add tx10-clock-x default && rc-update add tx10-clock default`.
Manage with `rc-service tx10-clock stop|restart` — do not `pkill` by name (a
name pattern matches the supervisor too).
