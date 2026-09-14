# GlucoTrack

An Android app that reads a **FreeStyle Libre 2** glucose sensor over NFC and keeps the
readings on the phone. Built for tracking blood sugar during pregnancy, where the official
app isn't available.

Everything runs locally. The app has **no `INTERNET` permission**, so the operating system
itself blocks network access — the data staying on the device is enforced by Android, not
promised in a privacy policy.

---

## Status: not yet tested against a real sensor

The decoding pipeline is covered by 38 automated tests, including a full decrypt-and-parse
round trip against a synthetic sensor image. But **no part of this has touched physical
hardware yet.** Until it has, treat every number it shows as unverified.

The first real scan is the test that matters. See [First run](#first-run) below.

## Safety

This is a personal tool, not a medical device, and it has no connection to Abbott.

Gestational diabetes is managed against specific numbers, and a wrong reading has real
consequences. **Keep using whatever your clinician asked you to use for treatment
decisions.** This app is for visibility and pattern-spotting between those measurements —
seeing which meals cause spikes, what overnight looks like, how a day actually went.

Two design rules follow from that, and they're deliberate:

- **A reading is never shown without its age.** A number from three hours ago is labelled as
  such, because a stale reading presented as current is the dangerous failure mode.
- **Unknown is shown as unknown.** Where the sensor didn't record something, the app says so
  rather than interpolating a plausible value.

## What it does

- **Now** — current glucose, trend arrow, rate of change, sensor age, and the last 8 hours.
- **History** — day-by-day chart with the target range shaded, plus that day's food.
- **Food** — log what was eaten; each entry is lined up against what glucose did over the
  following two hours, checked against the 1-hour and 2-hour targets.
- **Reports** — time in range, averages, variability, morning readings against the fasting
  target, and which foods produced the biggest rises.

Targets default to the **pregnancy** thresholds (range 63–140 mg/dL; fasting < 95, 1 h < 140,
2 h < 120), which are tighter than general diabetes ones. They're editable in Settings —
your clinician's numbers beat any default shipped in an app.

## Installing

No Android Studio needed. Every push builds a signed APK and publishes it here:

**→ [Download the latest build](../../releases/tag/dev)**

Open that page on the phone, download `glucotrack.apk`, and tap it. Android will ask you
to allow installing from this source — expected for an app that isn't from the Play Store.

Requires an Android phone with NFC, running Android 8.0 or newer.

New builds install straight over old ones and keep their stored readings, because the debug
signing key is fixed in the repository rather than regenerated per build. That key signs debug
builds only and is not a secret; it must never be used to sign anything for distribution.

## First run

The sensor in hand is unstarted, so the order is:

1. Open the app. NFC scanning is armed the whole time the app is in the foreground — there's
   no scan button to press.
2. Hold the **top back** of the phone against the sensor. The NFC antenna is a small area and
   its position varies by handset; if nothing happens, move the phone slowly around.
3. The app reports the sensor is unstarted and offers **Start this sensor**. Starting begins
   its 14-day life and cannot be undone, so it never happens from an accidental tap — you
   arm it, then tap the sensor again.
4. The sensor warms up for about an hour before it reports glucose.
5. After that, scan whenever you want a reading.

**Scan at least every 8 hours.** The sensor stores only its last 8 hours of history, so
readings between scans are recovered from that buffer. Longer gaps are lost permanently —
the chart will show a break rather than a guess.

## How it works

A Libre 2 is a passive NFC tag (ISO 15693) with 344 bytes of memory holding a 16-entry
1-minute ring and a 32-entry 15-minute ring. On the Libre 2 that memory is encrypted with a
key derived per block from the tag's UID and its patch info. The app:

1. reads the patch info and all 43 memory blocks over NFC,
2. decrypts them,
3. validates three independent CRCs before trusting anything,
4. unpacks the bit-packed measurement records, and
5. applies the sensor's own factory calibration — reconstructing thermistor temperature,
   compensating the raw count for it, then applying the per-sensor fit.

The decryption scheme is the one reverse-engineered and published by the open-source
diabetes community (DiaBLE, xDrip+, LibreTools). This is interoperability with a sensor you
own, reading your own data.

### Why the tests are built the way they are

Wrong constants in this pipeline don't throw — they produce a number that looks entirely
reasonable and is wrong. So:

- the 1023-entry calibration tables are **extracted mechanically**, never typed by hand;
- the CRC table is **computed at runtime** and pinned against the published values;
- the whole pipeline is checked against a **synthetic sensor image** built by an independent
  implementation of the same algorithm — valid CRCs, populated rings, realistic calibration.

## Limitations

- **NFC only.** The Libre 2 can also stream over Bluetooth once per minute, which would
  remove the tapping and allow high/low alarms. That's the natural next step and the
  groundwork is in place, but it isn't built yet.
- **8-hour memory.** Covered above — scan regularly or lose the gap.
- **No alarms.** Without BLE there's nothing running in the background to alarm from.
- **Libre 2 only.** Libre 3 uses a different protocol entirely.

## Building locally

Needs JDK 17+ and the Android SDK:

```bash
./gradlew testDebugUnitTest   # 38 tests, no device needed
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

## Layout

```
app/src/main/java/com/glucotrack/
  sensor/     NFC transport, decryption, CRC, FRAM parsing, factory calibration
  data/       Room storage, repository, settings
  analysis/   targets, time-weighted statistics, meal impact, fasting readings
  ui/         Compose screens and chart
```
