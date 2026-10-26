<div align="center">

<img src="docs/images/icon-512.png" alt="IITD Companion app icon" width="112">

# IITD Companion

**The IIT Delhi app for Webmail, Moodle, RollCall and eAcademics: one sign-in, every portal.**

A free, open-source Android and iPhone app for IIT Delhi students. It signs you in to IIT Delhi's portals with your Kerberos ID and keeps them all one swipe apart.

[Support](https://n06-nitin.github.io/iitd_companion/support.html) · [Privacy policy](https://n06-nitin.github.io/iitd_companion/privacy.html) · [Report a bug](https://github.com/n06-nitin/iitd_companion/issues) · [Built by an IITD student](#about-the-developer)

![Platform: Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Platform: iOS](https://img.shields.io/badge/iOS-17%2B-000000?logo=apple&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Swift](https://img.shields.io/badge/Swift-SwiftUI-F05138?logo=swift&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-blue)

<img src="docs/images/feature-1024x500.png" alt="IITD Companion: IIT Delhi Webmail, Moodle New, Moodle and RollCall in one app" width="720">

</div>

## What is IITD Companion?

IITD Companion is an unofficial mobile app that puts IIT Delhi's most-used websites into one place: **Webmail**, **Moodle New**, **Moodle**, **RollCall** and **eAcademics**. You save your Kerberos ID and password once, and the app fills in each portal's login page for you. Each portal opens in its own tab, and you swipe between them.

It is made by an IIT Delhi student, for IIT Delhi students: [Nitin Singh](#about-the-developer), a third-year student living in Aravali Hostel, under his studio [XLON Labs](https://xlonlabs.com). It is not affiliated with or endorsed by IIT Delhi.

## Screenshots

<p align="center">
  <img src="docs/images/screenshot-1-login.png" alt="Save your Kerberos ID once" width="160">
  <img src="docs/images/screenshot-2-autosign.png" alt="Automatic sign-in to IIT Delhi Webmail and Moodle New" width="160">
  <img src="docs/images/screenshot-3-swipe.png" alt="Swipe between IIT Delhi portals" width="160">
  <img src="docs/images/screenshot-4-captcha.png" alt="Type only the CAPTCHA on Moodle and RollCall" width="160">
  <img src="docs/images/screenshot-5-rollcall.png" alt="RollCall QR attendance scanner" width="160">
</p>

## Features

- **One login for every portal.** Save your Kerberos ID and password once. The app signs you in to each IIT Delhi portal.
- **Hands-free sign-in to Webmail and Moodle New.** These have no CAPTCHA, so you are signed in without touching anything.
- **Type only the CAPTCHA on Moodle and RollCall.** The app fills in your ID and password; you type the CAPTCHA yourself. It never solves CAPTCHAs.
- **RollCall attendance with the QR scanner.** The camera works inside the RollCall tab for marking attendance.
- **eAcademics** opens from the ⋮ menu, signed in through the same IIT Delhi OAuth page as RollCall.
- **Swipe between tabs.** Every portal stays loaded, so switching is instant. Tapping the open tab again takes that portal back home.
- **Downloads that land where you expect.** Moodle and Webmail files go to Downloads on Android and to the Files app on iPhone.
- **Native on both platforms.** Kotlin and Jetpack Compose on Android, Swift and SwiftUI on iOS. Not a cross-platform wrapper.

## Supported IIT Delhi portals

| Portal | Address | Sign-in |
|---|---|---|
| Webmail | `webmail.iitd.ac.in` | Automatic |
| Moodle New | `moodlenew.iitd.ac.in` | Automatic |
| Moodle | `moodle.iitd.ac.in` | You type the CAPTCHA |
| RollCall | `rollcall.iitd.ac.in` via `oauth.iitd.ac.in` | You type the CAPTCHA |
| eAcademics | via `oauth.iitd.ac.in` | You type the CAPTCHA |

## Privacy and security

- **Your password never leaves your phone** except to be typed into IIT Delhi's own login pages, over HTTPS, on those exact addresses.
- **Encrypted at rest.** On Android, with an AES-256 key in the Android Keystore. On iPhone, in the iOS Keychain, for that device only (never synced to iCloud).
- **No analytics, no ads, no tracking, no developer server.** The app talks only to IIT Delhi.
- **Excluded from backups.** Your saved login is not copied to Android backups or restored onto another iPhone.
- **One tap to remove it.** ⋮ menu → *Forget login & sign out* deletes your login and every portal cookie.

The code is open, so you can check every line of this yourself. Full details are in the [privacy policy](https://n06-nitin.github.io/iitd_companion/privacy.html).

## Download

| Platform | Status |
|---|---|
| Android (8.0 and later) | Google Play: in testing, public release coming soon |
| iPhone (iOS 17 and later) | App Store: in review, public release coming soon |

Want to try it early? [Open an issue](https://github.com/n06-nitin/iitd_companion/issues) or email [nitin@xlonlabs.com](mailto:nitin@xlonlabs.com).

## Frequently asked questions

### Is IITD Companion an official IIT Delhi app?
No. It is an unofficial, independent app and is not affiliated with or endorsed by IIT Delhi. For problems with the portals themselves, such as your Kerberos account or a course page, contact IIT Delhi's Computer Services Centre.

### Who made IITD Companion?
Nitin Singh, a third-year IIT Delhi student in Aravali Hostel and founder of XLON Labs.

### Is it safe to give the app my Kerberos password?
Your password is encrypted on your phone and is only ever typed into IIT Delhi's own login pages. It is never sent to the developer or anyone else, and the full source code is in this repository.

### Why do I still have to type a CAPTCHA?
Moodle, RollCall and eAcademics show a CAPTCHA on their login pages. The app fills in your ID and password but leaves the CAPTCHA to you. Webmail and Moodle New have no CAPTCHA, so they sign you in by themselves.

### Can I mark RollCall attendance with this app?
Yes. Open the RollCall tab and allow camera access when asked. The QR scanner on RollCall's attendance page then works as it does in a browser.

### Where do my Moodle downloads go?
On Android, to your Downloads folder. On iPhone, to the Files app, under *On My iPhone › IITD Companion › Downloads*.

### Is the app free?
Yes. It is free, has no ads, and is open source under the MIT license.

More answers are on the [support page](https://n06-nitin.github.io/iitd_companion/support.html).

## Build from source

The repository has two native apps that share no code.

```
android/   Kotlin + Jetpack Compose, WebView per portal (min SDK 26, target SDK 36)
ios/       Swift + SwiftUI, WKWebView per portal (iOS 17+), project generated with XcodeGen
docs/      Privacy policy and support pages, served on GitHub Pages
```

**Android**

```sh
cd android
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
```

Release signing reads `android/keystore.properties`, which is not in git. Debug builds need nothing extra.

**iOS**

```sh
cd ios
xcodegen generate              # brew install xcodegen
open IITDCompanion.xcodeproj
```

## About the developer

IITD Companion is designed and built by **Nitin Singh**, a third-year student at **IIT Delhi** living in **Aravali Hostel**, and founder of **[XLON Labs](https://xlonlabs.com)**. Nitin builds native apps and developer tools focused on privacy and on removing small daily frictions.

- Website: [nitinsingh.life](https://nitinsingh.life)
- Company: [xlonlabs.com](https://xlonlabs.com)
- GitHub: [@n06-nitin](https://github.com/n06-nitin)
- Email: [nitin@xlonlabs.com](mailto:nitin@xlonlabs.com)

## More projects by Nitin Singh

| Project | What it does | Link |
|---|---|---|
| **Postie** | Send files and text between all your own devices: phone, tablet, laptop and browser. LAN first, end-to-end encrypted. | [postie.xlonlabs.com](https://postie.xlonlabs.com) |
| **Serverless** | Scan a QR code and connect peer-to-peer for group chat, images and video, with no server in between. | [serverless.xlonlabs.com](https://serverless.xlonlabs.com) |
| **Nebula** | Turn your knowledge into a hosted MCP server that any AI assistant connects to with one link. | [xlonlabs.com/nebula](https://xlonlabs.com/nebula) |
| **Nitinverse** | Nitin's personal site and playground. | [nitinsingh.life](https://nitinsingh.life) |
| **XLON Labs** | The company home: every project, plus the blog. | [xlonlabs.com](https://xlonlabs.com) |

## License

[MIT](LICENSE) © 2026 Nitin Singh (XLON Labs)

<sub>IITD Companion is not affiliated with, endorsed by or sponsored by the Indian Institute of Technology Delhi. "IIT Delhi" and the portal names belong to their owners and are used only to describe what the app works with.</sub>
