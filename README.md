# GlucoBro

A simple, lightweight Android glucose companion for LibreLinkUp.

GlucoBro started because I wasn't happy with the glucose app I was using. I wanted something reliable, battery-friendly and simple: show me my glucose data, give me useful graphs and statistics, and give me meaningful alarms that I can actually control.

So I built the app I wanted to use.

GlucoBro is free and open source.

## Features

- Live glucose readings from LibreLinkUp
- Current glucose, trend and change
- Persistent Android notification
- Glucose reading age / last updated time
- Glucose history graphs
- 24-hour, 3-day and 7-day statistics
- Time in Range
- Average, lowest and highest glucose
- Low / In Range / High breakdown
- Sensor information
- Configurable glucose alarms
- Individually adjustable alarm volume
- Alarm test function
- Configurable Urgent Low threshold
- Urgent Low acknowledgement and repeat logic
- Designed to remain useful without unnecessary background activity
- No adverts
- No subscription

## Glucose Alarms

GlucoBro includes three independently configurable glucose alarms:

### Urgent Low

The default Urgent Low threshold is **2.9 mmol/L**.

The threshold can be adjusted between **2.5 and 3.9 mmol/L**.

Setting an Urgent Low threshold below the default requires explicit acknowledgement within the app.

Urgent Low alarms use additional repeat and acknowledgement logic intended to make accidentally dismissing an important low-glucose warning more difficult.

### Low

The Low threshold is user configurable and must remain above the Urgent Low threshold.

### High

The High threshold is user configurable up to **18.0 mmol/L** and must remain above the Low threshold.

Each alarm has its own enable/disable control, test function and volume setting.

## Screenshots

Screenshots coming soon.

## Requirements

- Android 8.0 (API 26) or newer
- Internet connection
- A working LibreLinkUp account

GlucoBro obtains glucose information using LibreLinkUp. Availability may therefore depend on the LibreLinkUp service and could be affected by changes made to that service.

## Installation

Pre-built APK releases will be available from the **Releases** section of this repository.

Android may ask for permission to install applications from outside the Google Play Store.

Alternatively, GlucoBro can be built from source using Android Studio.

## Building From Source

1. Clone or download this repository.
2. Open the project in Android Studio.
3. Allow Gradle to synchronise and download the required dependencies.
4. Build and run the `app` configuration on an Android device.

## Privacy

GlucoBro does not require a GlucoBro account and does not operate its own cloud service.

LibreLinkUp credentials are entered on the device so the app can retrieve glucose information from LibreLinkUp.

Please see `PRIVACY.md` for further information.

## Important Safety Information

**GlucoBro is an independent open-source project and is not affiliated with, endorsed by, or supported by Abbott or FreeStyle Libre.**

GlucoBro should not be considered a replacement for the official Libre applications, glucose monitoring hardware, or professional medical advice.

Do not make treatment decisions solely from information or alarms provided by GlucoBro.

If a reading or alarm does not match how you feel or what you expect, verify your glucose using the methods recommended by your glucose monitoring system and healthcare team.

Software can fail. Networks can fail. Phones can stop background applications, lose connectivity, run out of battery or be silenced. LibreLinkUp itself may also be unavailable.

**Do not rely on GlucoBro as your only method of detecting hypoglycaemia or hyperglycaemia.**

## Why Open Source?

GlucoBro began as a personal project.

I wanted a glucose app that was simple, reliable and didn't bury the information I cared about underneath unnecessary features.

Rather than turn it into another paid diabetes app or subscription, I've chosen to make GlucoBro free and open source.

If it's useful to somebody else, brilliant.

Contributions and bug reports are welcome, but the aim of the project will remain the same:

**Keep it simple, useful and reliable.**

## Contributing

Bug reports, suggestions and code contributions are welcome.

Please open an Issue if you find a problem.

If you'd like to contribute code, please open a Pull Request with a clear explanation of what the change does and why.

Features that add unnecessary complexity may not be accepted. GlucoBro is intentionally designed to stay simple.

## Disclaimer

This software is provided without warranty.

Use of GlucoBro is entirely at your own risk. The developers and contributors cannot guarantee the accuracy, availability or timeliness of glucose readings, notifications or alarms.

Always follow the instructions and safety guidance supplied with your glucose monitoring system.

## Licence

GlucoBro is free and open-source software released under the **GNU General Public License v3.0 (GPLv3)**.

See the `LICENSE` file for the full licence text.

---

**GlucoBro**

Simple glucose information. Meaningful alarms. No bullshit.
