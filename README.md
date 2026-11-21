# Daniel's Car Mechanic Scanner

An Android app for ELM327-compatible OBD2 adapters, Classic Bluetooth or BLE.

## What it does

- **Home**: your car's name and an animated top-down drawing of it (body style and doors from the VIN, paint color you pick), with headlights glowing and doors opening during tests. Below it: health card, quick actions, a status row per system, and a flashlight button.
- **Garage**: every car you've connected to or added by VIN, each with its own details, color, tests, body signals, maintenance and history. Tap the car name on Home to switch.
- **Live view and body data**: set up signals from your car's body computer once (the Watch tool highlights the byte and bit that change when you open a door or flip a switch), and the drawing mirrors the real car: lights, high beams, fog, brake, reverse, turn signals and hazards blinking at your car's measured rate, dome light, doors, trunk, hood, windows, sunroof, locks, wipers, horn, garage door button, burned-out bulbs and engine running.
- **Emissions inspection readiness** and **freeze frame** snapshots on the Codes screen.
- **Maintenance**: oil, filters and fluids by miles or months, with the odometer read from newer cars.
- **Scan history**: every scan saved per car, shareable as a text report.
- **Recall alerts**: a daily background check for new recalls on every car in your garage, with a phone notification that opens the recall.
- **Returning-code alerts**: if a code you cleared comes back, you get a notification and a red card on Home and Codes.
- **Trips & MPG**: live and average MPG, distance, time, fuel used and cost, with a saved trip log per car.
- **Home screen widget**: your car in its color, latest health result, recall count and mileage.
- **Vehicle history**: one tap copies your VIN and opens Carfax, AutoCheck, NICB VINCheck, NMVTIS or NHTSA's VIN recall check.
- **Vehicle & recalls**: details decoded from the VIN by NHTSA, paint color, manual VIN entry, open safety recalls linking to nhtsa.gov, and **Control your car**: a button for each light, door, window and more that sends that part's real command (from its active test) and animates the drawing. Parts without a command show + and open a pre-filled form to add one.
- **Related for your car**: tap Look up on any code, or Related on any amber or red result, for recalls on the same system plus web, video, owner-forum and service-bulletin searches for your exact year, make and model.
- **Code library**: 2,066 standard code definitions built in, so they work offline.
- **About & settings**: version and build date, System / Light / Dark appearance, color legend, privacy, safety and credits.
- **Health check**: one tap, about 20 seconds at warm idle. Judges battery and charging, cooling, fuel trims (per bank, and whether both banks agree), oxygen sensors and catalytic converter, idle quality, vacuum, timing and codes. Every amber or red result explains what it means and lists the likely causes, most common first.

- **Connect**: lists paired Classic adapters and scans for BLE ones, then auto-detects the car's protocol and reads the VIN.
- **Codes**: check engine light status, stored, pending and permanent codes with descriptions, a web lookup per code, and clearing.
- **All modules**: knocks on every address on the car's network and lists every computer that answers (ABS, airbags, transmission, body, climate, cluster, steering...). Reads each one's name and codes in UDS, KWP2000 or older Toyota format, with failure type (open circuit, short to ground...) and status (active, confirmed, pending, history). Clear per module or all at once. Optional deep scan of the Toyota/Lexus body network. Rename unknown modules once you know what they are.
- **Live data**: arc gauges that turn green, amber or red against normal ranges, including oxygen and air-fuel sensors. Tap a gauge to see what it measures and what is normal.
- **Tests**: your own actuator tests (fan, fuel pump, etc.) with a confirmation, an automatic time limit, and stop commands that always run.
- **Console**: send any raw command and see the reply. Quick buttons, copyable log, and a guard that blocks commands that could reset or reprogram a module unless Expert mode is on.

## Reading the code

Every file starts with a "WHAT THIS FILE DOES" note, and every function has a plain-English comment above it. Good places to start:

- `obd/Health.kt`: the normal ranges and likely causes. Change a number here to change when something turns amber or red.
- `obd/Modules.kt`: the all-module scanner.
- `net/Nhtsa.kt` and `net/Related.kt`: VIN decoding, recalls and the Related links.
- `ui/VehicleView.kt`: the car drawing and its animations.
- `obd/Pids.kt`: every sensor the app reads, with its formula and normal range.
- `ui/ScannerViewModel.kt`: what happens when you tap each button.
- `ui/Theme.kt`: all the colors.

## Build the APK

1. Install Android Studio (free) and open this folder with **File > Open**.
2. Let Gradle sync. If Studio offers to download an SDK or upgrade the Gradle plugin, accept.
3. **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
4. The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone and open it to install (allow "install unknown apps" for your file manager).

## Using it

1. Plug in the adapter, ignition on (engine can be off).
2. Classic adapters: pair in Android Bluetooth settings first, PIN usually 1234 or 0000. BLE adapters: tap **Scan for BLE**.
3. Tap your adapter. Connecting can take up to 15 seconds.

## Adding active tests

Actuator commands are specific to the manufacturer and module, so the app ships with none. Only add a command you've confirmed for your exact car.

A test has a module address (7E0 is usually the engine computer on CAN cars), start commands, stop commands, an optional keep-alive (3E00 keeps the session open), and a time limit.

Ways to find the right commands:
- Run Toyota Techstream with a Mini VCI cable while a second adapter in monitor mode (or a CAN sniffer) on a Y-splitter records the traffic, then copy what Techstream sends.
- Owner forums and published research for your model.
- Experiment in the Console with headers on (ATH1). The app explains rejections, e.g. "conditions not correct" or "security access denied".

Some modules need an extended diagnostic session (e.g. `1003`) before they accept actuator commands; put that as the first start command.

## Adapter notes

Cheap ELM327 clones often handle basic codes and live data but fail on manufacturer-specific commands. An OBDLink MX+ or Vgate iCar Pro is far more reliable for active tests.

## Internet

The app uses the internet only to decode the VIN and fetch recalls from NHTSA (U.S. government, free, no account), and to open links you tap. Results are saved on the phone so they still show offline.

## Safety

Car in Park, parking brake on, nothing near the fan or belts. Don't run tests while driving. Commands that reset, unlock, write or reprogram modules are blocked unless you turn on Expert mode; leave it off unless you know exactly what a command does.
