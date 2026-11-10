# AndroidAdBlock

Blocks ads and trackers in every app on an Android phone, without root.

It works the same way Blokada does: the app runs a local VPN that captures
only DNS lookups. Lookups for known ad and tracker domains are answered with
"no such address"; everything else is passed to a normal DNS server. No
traffic leaves the phone through a remote VPN server.

## Install

Every push to `main` builds the APK. Open the repo's **Releases** page on the
phone, download `AndroidAdBlock.apk` from **Latest build**, and install it
(Android will ask to allow installs from the browser).

## Use

Open the app, tap **Turn on**, and accept the VPN prompt. The full blocklists
(StevenBlack hosts and Peter Lowe's list) download on first start and refresh
every three days. Tap any name under **Recent lookups** to allow or block it.

## Limits

- Ads served from the same domain as the content (YouTube, most of
  Instagram and Facebook) cannot be blocked by DNS filtering.
- Android's **Private DNS** setting must be Off or Automatic; a custom
  provider bypasses the filter. The same goes for "Secure DNS" in Chrome.
- Only one VPN can run at a time on Android.

## Build locally

Open the folder in Android Studio, or run `gradle assembleDebug` with
Gradle 8.7 and JDK 17.
