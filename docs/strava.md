# Strava setup (personal use)

The app uploads rides as FIT files through the Strava API. Each user needs their own Strava API application.

## 1. Create the Strava API application

1. Open <https://www.strava.com/settings/api> (logged in with your Strava account).
2. Fill in:
   - **Application Name:** e.g. `IndoorTrain (personal)`
   - **Category:** Training
   - **Website:** any URL, e.g. `http://localhost`
   - **Authorization Callback Domain:** `localhost`
3. Save. Note the **Client ID** and **Client Secret**.

New Strava API applications are limited to one connected athlete (you). That is enough for personal use.

## 2. Add the credentials to the build

```bash
cp secrets.properties.example secrets.properties
# edit secrets.properties: STRAVA_CLIENT_ID=… and STRAVA_CLIENT_SECRET=…
./gradlew installDebug
```

`secrets.properties` is in `.gitignore` and must never be committed.

## 3. Connect

In the app, go to **Settings → Strava → Connect with Strava**, log in, and allow **"Upload your activities"**. Strava then redirects to `indoortrain://localhost/strava` and the app stores the tokens, encrypted with a key in the Android Keystore.

- **Upload a ride:** open **History → ride → Upload to Strava**, or switch on **Upload automatically** in Settings.
- **Duplicates:** if Strava already has the ride, the app links to the existing activity.
- **Disconnect:** revokes the app's access on Strava and deletes the tokens on the phone.

## What is uploaded

One FIT file per ride, containing:

- sport *cycling / indoor cycling*, marked as *trainer*
- per-second power, cadence, speed, distance and heart rate
- one lap per workout step

Heart rate is health data: it only leaves the phone when you upload a ride.

## Troubleshooting

- **"redirect_uri invalid" on the Strava page:** check that the callback domain in your Strava API settings is exactly `localhost`.
- **"Strava rate limit reached":** the limits are 100 requests per 15 minutes and 1000 per day. Wait and retry.
- **Play Store release:** the client secret must not ship in the APK. A small token-exchange backend is needed (see PLAN.md, phase 6).
