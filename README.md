# Собрано / Sobrano

Local packing lists for trips, people and bags. Original Android application by jonkryl; package `com.jonkryl.packinglist`, Android 7.0+ (API 24), target SDK 36.

Trips, item quantities, people, bags and packing marks stay on the device. Templates become fully editable trips. Repeated item names are allowed. Export uses Android's text share sheet. The app uses a Yandex Advertising Network banner; personalization preferences never disable advertising entirely.

## Build and verification

The project uses Java 17, Android Gradle Plugin 8.13.2, Gradle 8.13 and Android SDK 36. The checked-in wrapper verifies the Gradle distribution SHA-256. Gradle is limited to two workers and a 2 GB JVM.

`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` builds the debug app and tests. Debug ads use Yandex's demo unit.

`Android CI` runs unit tests and lint, then real emulator journeys on API 24 and 36. It saves instrumentation reports, process-restart proof, 200% system-font tests, logcat and screenshots from the app. The device script rejects an empty, crashed or failed test run.

## Signed release

Set repository/environment secrets `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_STORE_PASSWORD`, `UPLOAD_KEY_PASSWORD` and `UPLOAD_KEY_ALIAS`, plus repository variable `YANDEX_BANNER_ID` containing the app's real `R-M-…-…` banner ID. Keep the upload key and passwords outside the repository. Run `Signed Android release` with a fresh version code and public version name.

That workflow first passes the complete CI suite, then runs release lint and builds signed APK/AAB. Signing material is decoded only under `runner.temp` and removed after the job. Release builds reject missing signing material or a demo banner ID. The `signed-release-*` artifact contains both binaries, SHA-256 sums, exact upload-certificate checks, decoded package/version/min/target SDK proof, APK zip alignment and APK/AAB 64-bit ELF 16 KB checks.

CI artifacts and a GitHub download do not establish Google Play production publication or paid ad serving. Play track/country status and the Yandex app's Active status must be recorded from the actual consoles in the release manifest.

Contact: <jonkryl@gmail.com>.
