"""Check APK badging and the decoded AAB manifest agree with the requested release."""
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

package = "com.jonkryl.packinglist"
version_code = os.environ["VERSION_CODE"]
version_name = os.environ["VERSION_NAME"]
badging = Path("release-output/package.txt").read_text()
match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
if not match or match.groups() != (package, version_code, version_name):
    raise SystemExit("APK package or version does not match the requested release")
for label, expected in (("sdkVersion", "24"), ("targetSdkVersion", "36")):
    if f"{label}:'{expected}'" not in badging:
        raise SystemExit(f"APK {label} is not {expected}")
namespace = "{http://schemas.android.com/apk/res/android}"
manifest = ET.parse("release-output/aab-manifest.xml").getroot()
if (manifest.get("package"), manifest.get(namespace + "versionCode"), manifest.get(namespace + "versionName")) != (package, version_code, version_name):
    raise SystemExit("AAB package or version does not match the requested release")
uses_sdk = manifest.find("uses-sdk")
if uses_sdk is None or uses_sdk.get(namespace + "minSdkVersion") != "24" or uses_sdk.get(namespace + "targetSdkVersion") != "36":
    raise SystemExit("AAB min/target SDK does not match 24/36")
permissions = {permission.get(namespace + "name") for permission in manifest.findall("uses-permission")}
if "com.google.android.gms.permission.AD_ID" in permissions:
    raise SystemExit("AAB unexpectedly requests Android advertising ID")
if "com.google.android.gms.permission.AD_ID" in Path("release-output/merged-manifest.txt").read_text():
    raise SystemExit("APK unexpectedly requests Android advertising ID")
print("APK and AAB package, version, min/target SDK and advertising ID policy verified")
