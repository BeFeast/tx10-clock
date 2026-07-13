# External configuration & runtime status

This app is driven at runtime by a small, strictly validated JSON configuration
file it owns on external storage. This document is the contract for that
transport: where the file lives, how to update it safely, exactly what is
accepted, and what the app publishes back.

> Scope: these settings cover behaviour (boot behaviour, time format, digital
> seconds, display time zone) plus a small set of **bounded renderer
> selections**: approved colour-role *names*, clipping-safe digital text sizes,
> the compact-date toggle, and the burn-in shift enable/range. The file still
> carries **no raw visual values** — no hex/packed colours, geometry,
> typography, assets, or screenshot tolerances. Approved names resolve to the
> accepted contract's values inside the renderer mapping; everything else about
> the accepted visual contract is fixed and not configurable.

## Where the files live

The app reads and writes inside its own app-scoped external files directory:

```
getExternalFilesDir(null)/config.json   # input  (you write this)
getExternalFilesDir(null)/status.json   # output (the app writes this)
```

On the TX10 the operator alias for that directory is:

```
/sdcard/Android/data/com.befeast.tx10clock/files/
```

This location is **app-scoped external storage**, so reading and writing it
requires **no storage permission** at all — not `READ_EXTERNAL_STORAGE`, not
`WRITE_EXTERNAL_STORAGE`, not `MANAGE_EXTERNAL_STORAGE`. If external storage is
ever unavailable, the app transparently falls back to its internal files dir so
it never fails for lack of a config location.

## Updating `config.json` safely (atomic rename)

Write updates with a **same-directory temporary file plus an atomic rename**, so
the app never observes a half-written document — it always reads either the whole
previous file or the whole new one:

```sh
DIR=/sdcard/Android/data/com.befeast.tx10clock/files
# 1. write the new content to a temp file IN THE SAME DIRECTORY
printf '%s' "$NEW_JSON" > "$DIR/config.json.tmp"
# 2. atomically rename it over the live file (same filesystem => atomic)
mv -f "$DIR/config.json.tmp" "$DIR/config.json"
```

The temp file must be in the same directory as `config.json` so the rename stays
on one filesystem and is therefore atomic. The app uses this exact protocol for
its own `status.json` writes.

The app **reloads** `config.json` on every resume (and the boot receiver reads it
at boot). A configuration published via the atomic rename above is picked up the
next time the clock resumes.

## Accepted schema

`config.json` must be a single JSON **object**. Every key is optional; omitted
keys take their default. All fields are validated strictly (see below).

| Key                    | Type    | Default        | Notes |
|------------------------|---------|----------------|-------|
| `schemaVersion`        | integer | `1`            | If present must equal `1`. |
| `bootStart`            | boolean | `true`         | Auto-start the clock after reboot. |
| `use24Hour`            | boolean | `false`        | 24-hour vs 12-hour digital readout. |
| `showSeconds`          | boolean | `true`         | Show the second hand and seconds field. |
| `timeZone`             | string  | *(device zone)*| IANA id, e.g. `America/New_York`; omit to follow the device. |
| `digitalColor`         | string  | `"white"`      | Approved name for the digital time, hands, and numerals. |
| `dateColor`            | string  | `"grey"`       | Approved name for the compact date. |
| `tickColor`            | string  | `"silver"`     | Approved name for the minor tick marks. |
| `accentColor`          | string  | `"orange"`     | Approved name for the second hand and digital seconds. |
| `showDate`             | boolean | `true`         | Show the compact English date (e.g. `SUN, JUL 12`). |
| `digitalSizePercent`   | integer | `100`          | Main digital line size, `50`–`100` percent of the design size. |
| `secondarySizePercent` | integer | `100`          | Secondary line size, `50`–`100` percent of the design size. |
| `burnInEnabled`        | boolean | `true`         | Run the periodic whole-composition burn-in shift. |
| `burnInMaxShiftPx`     | integer | `8`            | Maximum shift amplitude, `0`–`8` design pixels. |

### Approved colour names

The four colour keys accept **only** these lowercase names, which resolve to the
accepted visual contract's palette inside the renderer:

| Name     | Contract role |
|----------|---------------|
| `white`  | primary (`#F5F5F7`) |
| `silver` | ticks (`#D1D1D6`) |
| `grey`   | secondary (`#A1A1A6`) |
| `orange` | accent (`#FF9F0A`) |

Raw colour values (`#RRGGBB`, `0xAARRGGBB`, etc.), other names, and other casings
are rejected. The pure-black background is fixed and not configurable, and the
approved set deliberately contains no name that would render content invisible
on it. Size percentages are bounded to `50`–`100` so text can never exceed the
clipping-safe design size, and `burnInMaxShiftPx` is bounded to the contract's
`±8 px` translation envelope.

Example:

```json
{
  "schemaVersion": 1,
  "bootStart": true,
  "use24Hour": true,
  "showSeconds": true,
  "timeZone": "Europe/Berlin",
  "digitalColor": "white",
  "accentColor": "orange",
  "showDate": true,
  "digitalSizePercent": 100,
  "burnInEnabled": true,
  "burnInMaxShiftPx": 8
}
```

## Strict, bounded ingestion

A document is **accepted** only if it passes every check. Anything else is
**rejected** and the app keeps its last accepted configuration (see
last-known-good below). Rejection reasons:

- **Oversized** — larger than 8 KiB.
- **Malformed** — not well-formed JSON, or trailing content after the object, or
  nested beyond a fixed depth bound.
- **Not an object** — the top-level JSON value is not an object.
- **Duplicate key** — the same key appears more than once (lenient parsers
  silently keep the last; this one rejects the document).
- **Unknown key** — any key outside the table above.
- **Wrong type** — e.g. `"bootStart": "true"` (string, not boolean), or a
  fractional `schemaVersion` / `digitalSizePercent` / `burnInMaxShiftPx`.
- **Out of range** — e.g. `schemaVersion` other than `1`, an unknown /
  over-long `timeZone`, a colour name outside the approved set, a size
  percentage outside `50`–`100`, or `burnInMaxShiftPx` outside `0`–`8`.

## Last-known-good

The app retains the most recently **accepted** configuration as an internal
last-known-good copy:

- First run with no `config.json`: the built-in defaults are in force.
- A valid `config.json`: becomes the new last-known-good.
- A rejected (or absent/unreadable) `config.json` on a later reload: the previous
  last-known-good stays in force; the coarse rejection reason is surfaced in
  `status.json`.

This means a bad edit can never break a running clock — it simply keeps showing
the last good configuration.

## `status.json` (verifier-safe output)

After each reload the app publishes a small, non-secret `status.json`. It exposes
**only** coarse runtime/config state and the effective (operator-supplied) config
values. It never contains device identifiers, serials, absolute paths, raw
rejected input, or secrets.

```json
{
  "statusSchemaVersion": 1,
  "configSource": "external",
  "lastReloadRejected": false,
  "lastRejectReason": null,
  "bootLaunch": false,
  "updatedAtEpochMillis": 1700000000000,
  "effectiveConfig": {
    "schemaVersion": 1,
    "bootStart": true,
    "use24Hour": true,
    "showSeconds": true,
    "timeZone": "Europe/Berlin",
    "digitalColor": "white",
    "dateColor": "grey",
    "tickColor": "silver",
    "accentColor": "orange",
    "showDate": true,
    "digitalSizePercent": 100,
    "secondarySizePercent": 100,
    "burnInEnabled": true,
    "burnInMaxShiftPx": 8
  }
}
```

- `configSource` — `external` if a valid `config.json` is in force, else `default`.
- `lastReloadRejected` / `lastRejectReason` — whether the most recent reload
  rejected a document, and the coarse reason enum (never the raw input).
- `bootLaunch` — whether this run was started by the boot receiver.
- `effectiveConfig` — the configuration actually in force (the last-known-good).

## Boot behaviour

`bootStart` defaults to `true`. After `BOOT_COMPLETED` the receiver:

1. returns immediately unless the app has already been launched at least once by
   delivery (it never hijacks the first boot after install),
2. reads only the internally accepted configuration (the strict model above, or
   defaults), and
3. launches the clock activity only when `bootStart` is enabled.

The receiver adds no root, overlay, launcher, kiosk, `DreamService`, or firmware
workaround. Reliable foreground auto-start on the TX10's API 29 OEM firmware
remains a separate live gate. The only permission the app declares for this is
the normal `RECEIVE_BOOT_COMPLETED`.

## Permissions & offline

The app is fully offline. It declares **only** `RECEIVE_BOOT_COMPLETED` and
requests no `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, or storage
permission.
