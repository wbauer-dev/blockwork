# Installing IndoorTrain on your phone

IndoorTrain is not in the Play Store. You install the APK file from the [GitHub releases](https://github.com/wbauer-dev/indoortrain/releases/latest) directly. This is called "sideloading".

**Requirements:**

- An Android phone with **Android 12 or newer**, and Bluetooth LE.
- A Bluetooth heart rate strap and/or a smart trainer with FTMS support. You can also try the app without them in simulation mode.

## 1. Download

1. On your phone, open <https://github.com/wbauer-dev/indoortrain/releases/latest>.
2. Under **Assets**, tap `IndoorTrain-x.y.z.apk`.
3. Confirm the download if the browser asks. It may warn that the file can be harmful; that warning appears for every APK file.

## 2. Allow the installation

Android only installs apps from outside the Play Store if you allow it for the app that opens the file (your browser or file manager):

1. Open the downloaded file: from the browser's download notification, or **Files → Downloads**.
2. Android shows **"For your security, your phone is not allowed to install unknown apps from this source"**. Tap **Settings**.
3. Switch on **Allow from this source**, then go back.

On Samsung phones you can also find this under **Settings → Apps → ⋮ → Special access → Install unknown apps**. The menu path differs slightly between Android versions.

You can switch the permission off again after installing.

## 3. Install

1. Tap **Install**.
2. **Google Play Protect** may say the app is unknown, because it isn't from the Play Store. Tap **More details → Install anyway**. If it offers to send the app for scanning, you can decline.
3. Open **IndoorTrain**.

## 4. First start

1. Allow **Nearby devices** (Bluetooth). It's needed to find the heart rate strap and the trainer. The app does not use your location.
2. Follow the **Getting started** card:
   1. Set your FTP: **Settings → FTP**, or ride the built-in **FTP ramp test**.
   2. Pair your devices: **Bluetooth icon**, then tap your heart rate strap and your trainer. Close other trainer apps (Zwift, Wahoo) first, because a trainer accepts only one controlling app.
   3. Ride a workout, or create a training plan.
3. When you start the first workout, allow **notifications**. The notification keeps the workout running with the screen off and has pause/skip buttons.

## Updates

Download the new APK from the releases page and install it the same way. Android installs it over the old version and **keeps your data**. That works because every release is signed with the same key.

## Verifying the download (optional)

Every release lists the **SHA-256 fingerprint of the signing certificate**, and includes a `.sha256` file with the checksum of the APK. To check the APK on a computer:

```bash
sha256sum -c IndoorTrain-x.y.z.apk.sha256
apksigner verify --print-certs IndoorTrain-x.y.z.apk   # compare the SHA-256 digest
```

## Install from a computer (alternative)

1. Turn on USB debugging on the phone (**Developer options**).
2. Connect the phone by USB and run:

```bash
adb install IndoorTrain-x.y.z.apk
```

## Troubleshooting

- **"App not installed" / "package conflicts with an existing package":** a version signed with a different key is already installed, for example a build you made yourself before the debug builds were renamed. Export rides you want to keep (History → ride → Export FIT), uninstall the old app, then install the release. Debug builds made now install separately as "IndoorTrain Dev".
- **The trainer connects but doesn't change resistance:** close other apps that control the trainer, update the trainer firmware with the manufacturer's app, then try **Settings → ERG test**.
- **No voice announcements:** install a voice in **Android settings → Text-to-speech output**. Announcements are in English.

## Uninstall

Long-press the app icon → **Uninstall**. This deletes all rides, workouts and plans stored in the app. Export anything you want to keep first.
