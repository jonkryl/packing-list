#!/usr/bin/env bash
set -euo pipefail
mkdir -p release-output
apk="release-output/sobrano-${VERSION_NAME}-${VERSION_CODE}.apk"
aab="release-output/sobrano-${VERSION_NAME}-${VERSION_CODE}.aab"
cp app/build/outputs/apk/release/app-release.apk "$apk"
cp app/build/outputs/bundle/release/app-release.aab "$aab"
build_tools="${ANDROID_HOME}/build-tools/36.0.0"
"$build_tools/apksigner" verify --verbose --print-certs "$apk" > release-output/apk-signature.txt
"$build_tools/zipalign" -c -P 16 -v 4 "$apk" > release-output/apk-zip-alignment.txt
jarsigner -verify -verbose -certs "$aab" > release-output/aab-signature.txt
grep -q 'jar verified' release-output/aab-signature.txt
keytool -printcert -jarfile "$aab" > release-output/aab-certificate.txt
curl --fail --silent --show-error --location https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar -o "$RUNNER_TEMP/bundletool.jar"
printf '%s  %s\n' 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29' "$RUNNER_TEMP/bundletool.jar" | sha256sum --check
java -jar "$RUNNER_TEMP/bundletool.jar" validate --bundle="$aab" > release-output/aab-validation.txt
java -jar "$RUNNER_TEMP/bundletool.jar" dump manifest --bundle="$aab" --module=base > release-output/aab-manifest.xml
java -jar "$RUNNER_TEMP/bundletool.jar" dump config --bundle="$aab" > release-output/aab-config.json
python3 - "$aab" <<'PY' > release-output/aab-page-alignment.txt
import sys, zipfile
from pathlib import Path
with zipfile.ZipFile(sys.argv[1]) as archive:
    native = any(name.endswith('.so') and any(abi in name.split('/') for abi in ('arm64-v8a', 'x86_64')) for name in archive.namelist())
config = Path('release-output/aab-config.json').read_text()
if native and 'PAGE_ALIGNMENT_16K' not in config:
    raise SystemExit('AAB does not request 16 KB native ZIP alignment')
print('AAB requests 16 KB alignment' if native else 'No 64-bit native libraries; ZIP page alignment not applicable')
PY
"$build_tools/aapt" dump badging "$apk" > release-output/package.txt
"$build_tools/aapt" dump xmltree "$apk" AndroidManifest.xml > release-output/merged-manifest.txt
python3 scripts/verify-package.py > release-output/package-verification.txt
python3 scripts/verify-ad-configuration.py "$apk" "$aab" > release-output/ad-configuration-verification.txt
keytool -list -v -keystore "$UPLOAD_KEYSTORE_FILE" -alias "$UPLOAD_KEY_ALIAS" -storepass:env UPLOAD_STORE_PASSWORD > "$RUNNER_TEMP/expected-certificate.txt"
python3 scripts/verify-certificate.py release-output/apk-signature.txt "$RUNNER_TEMP/expected-certificate.txt" > release-output/apk-certificate-verification.txt
python3 scripts/verify-certificate.py release-output/aab-certificate.txt "$RUNNER_TEMP/expected-certificate.txt" > release-output/aab-certificate-verification.txt
python3 scripts/verify-elf-alignment.py "$apk" > release-output/apk-native-page-alignment.txt
python3 scripts/verify-elf-alignment.py "$aab" > release-output/aab-native-page-alignment.txt
sha256sum "$apk" "$aab" > release-output/SHA256SUMS
printf 'commit=%s\nrun=%s/%s/actions/runs/%s\nversion_code=%s\nversion_name=%s\npackage=com.jonkryl.packinglist\nmin_sdk=24\ntarget_sdk=36\nbanner_id=%s\nads_test_mode=false\n' "$GITHUB_SHA" "$GITHUB_SERVER_URL" "$GITHUB_REPOSITORY" "$GITHUB_RUN_ID" "$VERSION_CODE" "$VERSION_NAME" "$YANDEX_BANNER_ID" > release-output/build-provenance.txt
