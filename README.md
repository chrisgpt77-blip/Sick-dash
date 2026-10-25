# Sick Dash

A sci-fi HUD dashboard for the **2022–2026 Toyota GR86 / Subaru BRZ** (ZN8 / ZD8). It runs on your Android phone and on the **Android Auto** screen, and reads live data straight off the car's CAN bus or OBD-II port. It also ships presets for the Nissan 370Z, and works with standard OBD-II on any car.

![Drive page](docs/screenshots/drive_1280x720.png)

| Shift · rev-match score | Engine |
|---|---|
| ![](docs/screenshots/shift_1280x720.png) | ![](docs/screenshots/engine_1280x720.png) |
| **Handling · G-force** | **Style · themes** |
| ![](docs/screenshots/handling_1280x720.png) | ![](docs/screenshots/style_1280x720.png) |

<details><summary>More: side camera, ultra-wide head unit, phone</summary>

![](docs/screenshots/drive_side_1280x720.png)
![](docs/screenshots/drive_wide_1920x720.png)
<img src="docs/screenshots/phone_portrait_1080x2340.png" width="300">
</details>

## What's on it

The **instrument column** on the left is always visible:
- Big glowing speed and gear boxes.
- Segmented RPM bar that turns amber, then flashes at your shift point.
- Status chips: SHIFT, BRAKE, CLUTCH, LAUNCH, COLD, LOW FUEL. Each lights only from real data.
- Oil temp, steering angle, throttle and brake bars.
- Clutch IN/OUT and coolant.

The pages are tabs along the bottom. On the car screen, tap the tabs on a touchscreen, or use the **page button** in the action strip.

| Page | |
|---|---|
| **Drive** | A glowing wireframe GR86 on a grid that streams by at road speed. Wheels spin at road speed, front wheels steer with the steering wheel and tail lights light up under braking. Tyre-pressure callouts sit on each wheel. Camera modes: Auto (orbit), Orbit (drag), Chase, 3/4, Side, Top. Side panel shows tyres, fuel, air temp, battery and best 0–60. |
| **Shift** | The last 10 s of revs with every gear change marked. Each change gets a **rev-match score**: how close the engine speed was to what the new gear needs when the clutch came out. Ratings: PERFECT 90+, CLEAN 75+, GOOD 55+, ROUGH 30+, MISSED. |
| **Engine** | Every reading with a 10-second live trace. |
| **Handling** | G-force radar with trail and peak ring. Peak lateral/brake/accel g, best and last 0–60 (or 0–100 km/h), top speed, distance. Tap the radar to reset the session. |
| **Bus** | Live CAN traffic: every ID, its rate and its bytes, with changing bytes lit. |
| **Style** | Theme presets with a live preview: Akasaka, Gazoo, WR Blue, Cyberpunk, Midnight, Matrix, Sunset, Ice, Daylight. |

## Connecting to a GR86 / BRZ

The 2022+ cars put a gateway in front of the OBD-II port, so you have two ways in:

| | What you get | Hardware |
|---|---|---|
| **OBD-II port** (driver's footwell) | Standard OBD requests only: RPM, speed, throttle, coolant, intake temp, voltage. About 10–20 updates/s. | Any OBD adapter. |
| **ASC connector** right of the glovebox (body CAN), or the **DCM connector** behind the glovebox | **Everything at 50–100 Hz**: RPM, speed, pedal, brake pressure, steering angle, gear, clutch, oil and coolant temp, air temp, fuel level, all four tyre pressures. | An ASC/DCM-to-OBD adapter (e.g. [Hachi Electronics](https://hachielectronics.com/products/2022-gr86-brz-asc-can-adapter)), plus a CAN-capable dongle on it. |

### Recommended: DauntlessOBD Enhanced (DOBD-GR) on the Hachi ASC adapter

[DauntlessOBD](https://dauntlessdevices.com/product/dobd-gr/) is a **Bluetooth LE** ELM327-compatible dongle with raw-CAN extensions. Sick Dash detects it and uses its fast path:
- `ATTP 81` for raw CAN at 500k.
- One `DD CRA` hardware filter per signal ID, so only the ~9 IDs the dash needs cross the Bluetooth link.
- `DD MF` / `DD MOF 1` for overflow-tolerant monitoring.
- `DD LEFAST 1` for faster LE transfers.

Its own features, like auto lock/unlock and remote windows, keep running on the dongle while the dash reads data.

1. Install the Hachi ASC adapter and plug the DauntlessOBD into it. Per Dauntless, any other adapter must supply constant 12 V and chassis ground on pin 4.
2. **Pair it with the official Dauntless OBD app first.** Pairing needs a button press on the dongle, and other apps can only talk to it afterwards. Then close that app, because a BLE dongle accepts one app at a time.
3. In Sick Dash → **Settings**:
   - **Car:** GR86 or BRZ.
   - **Data source:** *Bluetooth LE OBD*.
   - **Bluetooth adapter:** `DauntlessOBD_xxxxxx`. It's in the paired list, or use **Scan for Bluetooth LE adapters**.
   - **Read mode:** *Auto*.
4. Start the engine. The header shows **CAN** once body-CAN frames arrive.

**Read mode Auto** listens for broadcast traffic for 2.5 s. If frames arrive (ASC/DCM), it decodes raw CAN. If the bus is silent (the gatewayed factory OBD port), it falls back to OBD-II polling. You can move the dongle between ports without changing settings.

Other adapters that work:
- **OBDLink MX+** (Bluetooth Classic) or **OBDLink CX** (LE).
- **WiCAN** (Wi-Fi, in slcan or ELM mode).
- **CANable** (USB slcan).

The raw-CAN decode is `app/src/main/assets/profiles/toyota_gr86_brz_gen2.json`. It's built from the community-documented gen-2 mappings in [timurrrr/ft86](https://github.com/timurrrr/ft86/blob/main/can_bus/gen2.md) and [RaceChronoDiyBleDevice](https://github.com/timurrrr/RaceChronoDiyBleDevice/blob/master/can_db/ft86_gen2.md). The unit tests check it against the example frames documented there.

Notes on the decode:
- **Tyre pressures** assume a US (psi) cluster. For a bar cluster, set the tyre `scale` to `10` in the profile editor (**Settings → Edit CAN profile**).
- **6AT cars:** remove the `gear` signal. The app then estimates the gear from RPM and speed.
- **Accelerometers:** the car's are switched off by default because they're coarse. The phone's sensors drive the G-meter.

The app is **read-only**. It never transmits onto the CAN bus. In OBD mode it only sends standard OBD-II requests.

## Install

1. **Get the APK** from the [Releases page](../../releases): `SickDash-x.y.z.apk`. Open it on your phone and allow "install unknown apps". If you installed a debug build before, uninstall it first, because it's signed with a different key.
2. **Let Android Auto show it.** You only do this once:
   1. Open the Android Auto settings and tap **Version** about 10 times.
   2. Tap ⋮ → **Developer settings** and tick **Unknown sources**.
   3. Sick Dash then appears in the car's launcher. It registers as a navigation app, which is what lets it draw a full-screen custom display.
3. **Set it up on the phone:** tap an empty part of the dash, then **Settings**.
   - **Car:** GR86, BRZ or 370Z. This loads the factory gear ratios, final drive, tyre size, redline and default theme.
   - **Gearbox:** 6MT or 6AT.
   - **Data source:** your adapter.
   - **Theme, units, shift-light RPM.**

GR86 / BRZ factory numbers (Subaru spec sheet):

| | 6MT | 6AT |
|---|---|---|
| Ratios | 3.626 / 2.189 / 1.541 / 1.213 / 1.000 / 0.767 | 3.538 / 2.060 / 1.405 / 1.000 / 0.713 / 0.582 |
| Final drive | 4.100 | 3.909 |

Tyres are 215/40R18, or 215/45R17 on base cars. Redline is 7,500 rpm.

Car-screen controls:
- **Action strip:** page, theme, units, reset.
- **Map pan button:** drag to orbit the car.
- **Touchscreen head units:** taps on the HUD tabs and camera buttons work directly.

## Releases

Signed APKs are published on the [Releases page](../../releases). The **Release** workflow builds, tests, signs and publishes one in either of two ways:
- **Actions → Release → Run workflow**, then enter a version such as `1.0.1`. This creates the tag `v1.0.1`.
- Push a `v*` tag: `git tag v1.0.1 && git push origin v1.0.1`.

The signing key is never stored in this (public) repo. The workflow reads it from four repository secrets (**Settings → Secrets and variables → Actions**):
- `SICKDASH_KEYSTORE_BASE64`: the keystore file, base64-encoded. It can be split across `SICKDASH_KEYSTORE_BASE64`, `_2` and `_3` if one long value is hard to paste.
- `SICKDASH_KEYSTORE_PASSWORD`
- `SICKDASH_KEY_ALIAS`
- `SICKDASH_KEY_PASSWORD`

Keep using the same key: Android only installs an update over an app signed with the same key. Release builds only accept Android Auto (and Android Automotive) as hosts, via the car app library's allowlist of Google signatures. Debug builds accept any host, for the Desktop Head Unit and testing.

To sign locally, set `SICKDASH_KEYSTORE` (path), `SICKDASH_KEYSTORE_PASSWORD`, `SICKDASH_KEY_ALIAS` and `SICKDASH_KEY_PASSWORD`, then run `./gradlew assembleRelease`.

## Build

```
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # decoder tests + renders every page to app/build/screenshots/
```

## Code layout

`app/src/main/java/com/sickdash`:
- `can/`: adapters (Bluetooth LE serial, Bluetooth SPP, TCP, USB serial) and the ELM327 session (OBD / raw / auto, with DauntlessOBD extensions) and SLCAN session. Also OBD PIDs, and the JSON CAN profile format (byte or bit fields, scale/offset, lookup maps, clamps).
- `data/`: the live value store, car presets, the shift/rev-match tracker, session stats (0–60, peaks), 10-second history, phone G-sensor and the demo drive.
- `ui/DashRenderer.kt`: the whole HUD drawn on a Canvas, shared by the phone and Android Auto.
  - `ui/CarWireframe.kt`: the procedurally lofted GR86-proportioned wireframe.
  - `ui/Theme.kt`: the colour themes.
- `auto/`: the Android Auto app (NavigationTemplate surface at ~30 fps, with tap and pan support).

## Credits

- The visual style follows the 370Z tablet dash by **@cntdrive** on Instagram.
- The page structure and session timers take inspiration from [celica-dash](https://github.com/Kaito-Yagami/celica-dash) (MIT). No code was copied.
- GR86/BRZ CAN mappings come from [timurrrr/ft86](https://github.com/timurrrr/ft86) and Ken Houseal. The 370Z mappings come from [Knio/carhack](https://github.com/Knio/carhack).
- Fonts: [Oxanium](https://github.com/sevmeyer/oxanium) and [Share Tech Mono](https://fonts.google.com/specimen/Share+Tech+Mono), both under the SIL Open Font License. The licence texts are in `app/src/main/assets/fonts/`.
