# Sick Dash

A sci-fi HUD dashboard for the **2022–2026 Toyota GR86 / Subaru BRZ** (ZN8 / ZD8). It runs on your Android phone and on the **Android Auto** screen, and reads live data straight off the car's CAN bus or OBD-II port. It also ships presets for the Nissan 370Z, and works with standard OBD-II on any car.

![Drive page](docs/screenshots/drive_wide_1920x720.png)

| Start-up: boot | Start-up: system check |
|---|---|
| ![](docs/screenshots/boot_2_car_1280x720.png) | ![](docs/screenshots/boot_3_check_1280x720.png) |
| **Shift · rev-match and technique** | **Dyno · road pull** |
| ![](docs/screenshots/shift_1280x720.png) | ![](docs/screenshots/dyno_1280x720.png) |
| **Throttle · heel-toe** | **Timers** |
| ![](docs/screenshots/throttle_1280x720.png) | ![](docs/screenshots/timers_1280x720.png) |
| **Pages (task switcher)** | **Style · themes, colours, tiles** |
| ![](docs/screenshots/pages_1280x720.png) | ![](docs/screenshots/style_1280x720.png) |

<details><summary>More: clutch, VDC, systems, body, powered off, other cameras, phone</summary>

![](docs/screenshots/clutch_1280x720.png)
![](docs/screenshots/vdc_1280x720.png)
![](docs/screenshots/systems_1280x720.png)
![](docs/screenshots/body_demo_1280x720.png)
![](docs/screenshots/body_1280x720.png)
![](docs/screenshots/diff_asc_1280x720.png)
![](docs/screenshots/powered_off_1280x720.png)
![](docs/screenshots/boot_4_sweep_1280x720.png)
![](docs/screenshots/drive_1280x720.png)
![](docs/screenshots/drive_side_1280x720.png)
![](docs/screenshots/drive_rear34_1280x720.png)
![](docs/screenshots/drive_stock_1280x720.png)
<img src="docs/screenshots/phone_portrait_1080x2340.png" width="300">
</details>

## Start-up

Every launch plays a six-second start-up sequence. Tap anywhere to skip it.
1. **Boot.** A scan line sweeps the screen. The wordmark glitches in and the spec line types itself out. Then the wireframe car draws itself line by line: body first, then lights, then wheels.
2. **System check.** "CAN NODES · LISTENING" shows each module on the bus (ECM, ABS·VDC, steering, TCM, body, meter) with its live message rate. Then every signal is ticked off, group by group, with its live value. It ends in a verdict badge, e.g. **21 OK · 0 TO CHECK**.
3. **Rev sweep.** The rev bar sweeps to the limiter and back, and the dash fades in.

If the car is switched off for more than 10 s while the app is open, the dash shows **VEHICLE POWERED OFF**, with the last drive's distance, time and top speed. When the car comes back, the system check plays again. You can replay any animation from the Style page.

## What's on it

The **instrument column** on the left is always visible:
- Big glowing speed and gear boxes.
- Segmented RPM bar that turns amber, then flashes at your shift point.
- Status chips: SHIFT, BRAKE, CLUTCH, LAUNCH, COLD, LOW FUEL. Each lights only from real data.
- Oil temp, steering angle, and throttle, brake and fuel bars. The fuel bar turns amber below 15%.
- Clutch IN/OUT and coolant.

The **header** shows the link and its health ("■ CAN · 440 MSG/S · 22 SIGNALS · 22 VERIFIED"). It also shows LOCKED and BELT states when the car reports them, and REC while recording. The **PAGES** button opens a task switcher with every page as a card.

**Pop-ups** appear for engine start, each scored shift ("3 → 2 · PERFECT"), launch armed, 0–60 and quarter-mile results, clutch slip, over-rev, cold engine, low fuel, lock/unlock and recording.

The pages are tabs along the bottom (two rows on a phone held upright). On the car screen you can tap the tabs on a touchscreen, or use the **page** and **Pages** buttons in the action strip.

| Page | |
|---|---|
| **Drive** | A glowing wireframe GR86 with the real car's details: trapezoid grille, swept headlights, corner fangs, the fender vent and rocker line, wraparound L tail lights, ducktail and twin exhausts. It's depth-faded so it reads as a solid car. Settings let you choose 5-spoke or stock 10-spoke wheels, and a lowered stance with lip, side skirts and raised ducktail. The car sits on a grid that streams by at road speed. Wheels spin at road speed and the front wheels steer. Tail lights light under braking and headlights follow the light switch. Callouts on each wheel show tyre pressure (and wheel speed when available). Camera modes: Auto (orbit), Orbit (drag), Chase, 3/4, Side, Top. On wide screens, six shortcut tiles sit beside the car; you choose which on the Style page. |
| **Body** | **Body systems**, laid out like the reel. Fifteen state tiles: driver and passenger doors, trunk, locks, headlights, turn/hazard, wipers, seatbelt, park brake, brake lights, reverse, engine, VDC, track mode and cruise. Each tile shows its CAN id; active states light in their group colour (body, lights, safety, drivetrain) and anything needing attention lights red. Below that: tyres and vehicle (odometer, estimated range, fuel, average and current economy, brake pressure); this drive (time, moving time, distance, average and top speed, max rpm, max lateral g, hardest stop, fuel used, downshifts); chassis and wheels (wheel speeds, rear slip, slip angle β, yaw rate, lateral g, steering, counter-steer, engine-to-wheel ratio); and the bus (link, messages/s, bus load, CAN ids, signals, and a log of recent events). |
| **Handling** | G-force radar with trail and peak ring. Peak lateral/brake/accel g, best and last 0–60 (or 0–100 km/h), top speed, distance. Tap the radar to reset the session. |
| **Diff** | All four wheel speeds, the rear left–right split against what the yaw rate expects, rear-vs-front slip, and whether the Torsen LSD is biasing. Needs wheel speeds (id 0x13A), which come from the DCM connector, not the ASC; the page says so if they're missing. |
| **Shift** | The last 10 s of revs with every gear change marked. Each change gets a **rev-match score**: how close the engine speed was to what the new gear needs when the clutch came out. Ratings: PERFECT 90+, CLEAN 75+, GOOD 55+, ROUGH 30+, MISSED. It also counts your **heel-toe** downshifts, **throttle blips** and **double-clutches**. |
| **Clutch** | Engine rpm against the gearbox speed implied by road speed and gear, shown as two rings. Also the difference, slip %, a friction heat estimate, slip events and a 30-second slip trace. |
| **Throttle** | Throttle, brake and rpm traces over 10 s, with heel-toe overlap shaded. Also time at full throttle. |
| **Engine** | Twelve readings with 10-second live traces: rpm, pedal, speed, coolant, oil, air temp, battery, fuel, ignition timing, mass airflow, lambda and fuel trims. |
| **Dyno** | Wheel power and torque against rpm, worked out from acceleration, mass, drag and rolling resistance. Do a full-throttle pull in a gear and the curve appears, with your best pull kept underneath. |
| **VDC** | Balance: the yaw rate the steering asks for (bicycle model) against what the car actually does. A meter reads UNDERSTEER, NEUTRAL, OVERSTEER or DRIFT, with a 10-second trace and time spent oversteering. |
| **Timers** | Automatic from a standstill: 0–60 mph, 0–100 km/h, 0–100 mph, ⅛ and ¼ mile (with trap speed), and 60–0 braking distance. Best and last for each. The **RECORD** button logs every signal to CSV at 20 Hz. |
| **Systems** | Every signal with its value, its source (e.g. `CAN 0x40`, `OBD 0C`, `PHONE IMU`), its update rate and a status: OK, CHECK, STALE or NOT MAPPED. |
| **Bus** | Live CAN traffic: every ID, its rate and its bytes, with changing bytes lit. |
| **Style** | Theme presets (Akasaka, Gazoo, WR Blue, Cyberpunk, Midnight, Matrix, Sunset, Ice, Daylight) with a live preview. Colour pickers for the primary, secondary and car-line colours. Buttons to play each animation. The six Drive-page tiles. |

Nothing is faked. A signal the car doesn't send (or that isn't in the CAN profile yet) shows as NOT MAPPED. On the GR86, doors, trunk, locks, belt, headlights, turn signals, wipers, odometer, VDC, track mode, cruise and park brake have no publicly documented IDs yet; to fill them in, use **Settings → Learn body signals** (below). Range is an estimate from fuel left and this drive's economy (or the car's rated economy until there's enough data). Fuel used comes from mass airflow when the car reports it (OBD), otherwise from the fuel level dropping; the instant economy figure needs mass airflow. **Demo** mode simulates every signal, so you can see every feature without a car.

### Learn body signals

A step-by-step wizard that finds your car's body signals on the bus:
- driver and passenger doors, trunk, door locks, seatbelt and park brake
- headlights, turn signals and hazards, and wipers
- VDC, Track mode and cruise

1. Park with the engine running (or the ignition on) and the adapter on the ASC or DCM connector. Read mode must be Auto or Raw CAN.
2. Open **Settings → Learn body signals**. While it's open, the adapter's ID filters are switched off so the whole bus comes through.
3. For each control, the wizard asks you to work it a few times ("Open the driver's door" → Record, "Close all the doors" → Record, …). It records every bit on the bus while you do. A bit counts as a match when it reads the same every time the control is in the same position, and differently in different positions. The positions come in a deliberately uneven order, so a value that just creeps up over time can't pass for a switch.
4. It shows the best match with a live reading. Work the control again and check the reading follows. Then **Use it**, try the **Next match**, or **Skip**.
5. At the end, **Save** writes the kept signals into your CAN profile for this car, replacing any earlier ones. You can see or undo them in **Settings → Edit CAN profile**.

Some controls may not be on the connector you're using. The wizard reports those as not found rather than guessing.

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
- **Fuel level** is damped over about 20 s, like the factory gauge, because the raw sender sloshes several percent in corners and under braking. A new reading after the car has been off (for example after filling up) shows straight away.

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
- **Action strip:** page (next page), Pages (task switcher), Theme, Reset.
- **Map pan button:** drag to orbit the car.
- **Touchscreen head units:** taps on the HUD tabs and camera buttons work directly.
- **Full screen:** the dash fills the whole car screen, and Android Auto's buttons float over it, hiding when you're not touching the screen. If your car's buttons never hide and cover something, turn off **Settings → Fill the car screen** to keep the dash clear of them.

## Spotify and Google Maps

![Drive page with the next turn and now playing (demo)](docs/screenshots/demo_drive_wide_1920x720.png)

The Drive page shows what's playing on the phone, with album art, a progress bar and previous/play-pause/next buttons. It works with Spotify, YouTube Music and any other player, because it reads Android's media sessions. A **Music** tile can also go beside the car (pick it on the Style page); tapping it plays or pauses. A "NOW PLAYING" pop-up announces each new track.

While Google Maps is navigating, the next turn shows as a card on the Drive page (arrow, distance, instruction, ETA). On every other page it replaces the link chip in the header, e.g. `▶ 0.3 MI · TURN RIGHT ONTO MAIN ST`. It's read from Maps' turn-by-turn notification. Maps doesn't document that notification, so if a Maps update changes its layout the card may show the wrong text or nothing.

**Turn it on:** Settings → **Show Spotify and Google Maps on the dash** → switch on Sick Dash. This gives Sick Dash notification access, which Android requires for both. Sick Dash only looks at Maps' navigation notification and the media sessions; it ignores every other notification and stores nothing. On Android 13 and later, Android greys the switch out for apps installed from an APK: first open App info for Sick Dash → ⋮ → **Allow restricted settings**.

**Limits on Android Auto:**
- Sick Dash can't draw Google Maps' own map. The map stays in the Maps app.
- Sick Dash and Google Maps are both navigation apps, and Android Auto shows one at a time full-screen. Android Auto's split screen can still show Spotify's media card next to Sick Dash.
- Whether Maps' turn notification reaches the phone while Maps is navigating on the car screen hasn't been tested yet. The setup that's expected to work best is the reel's: Sick Dash on a phone or tablet on the dash, with Android Auto running Google Maps and Spotify on the car screen.

In demo mode, a made-up route and song show so you can see both cards.

## Drive logs

Tap **RECORD** on the Timers page to log every signal to CSV at 20 Hz. Tap again to stop. To send the newest log anywhere (Drive, email, a spreadsheet), use **Settings → Share latest drive log**. Logs are kept in the app's own storage, under `Android/data/com.sickdash/files/logs`.

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
- `data/`: the live value store (with each signal's source), car presets, the shift/technique tracker, `Analytics` (clutch slip, VDC balance, dyno, drag timers), events, the CSV recorder, 10-second history, phone G-sensor and the demo drive.
- `ui/DashRenderer.kt`: the HUD frame drawn on a Canvas, shared by the phone and Android Auto.
  - `ui/Boot.kt`: the start-up animation and system check.
  - `ui/Overlays.kt`: pop-ups, the task switcher, Drive tiles and the powered-off screen.
  - `ui/PagesVehicle.kt`, `ui/PagesPowertrain.kt`, `ui/PageStyle.kt`: the pages.
  - `ui/CarWireframe.kt`: the procedurally lofted GR86-proportioned wireframe.
  - `ui/Theme.kt`: the colour themes.
- `auto/`: the Android Auto app (NavigationTemplate surface at ~30 fps, with tap and pan support).

## Credits

- The visual style follows the 370Z tablet dash by **@cntdrive** on Instagram.
- The page structure and session timers take inspiration from [celica-dash](https://github.com/Kaito-Yagami/celica-dash) (MIT). No code was copied.
- GR86/BRZ CAN mappings come from [timurrrr/ft86](https://github.com/timurrrr/ft86) and Ken Houseal. The 370Z mappings come from [Knio/carhack](https://github.com/Knio/carhack).
- Fonts: [Oxanium](https://github.com/sevmeyer/oxanium) and [Share Tech Mono](https://fonts.google.com/specimen/Share+Tech+Mono), both under the SIL Open Font License. The licence texts are in `app/src/main/assets/fonts/`.
