"""Verify the real own banner ID is compiled in both artifacts and release test mode is false."""
import os
import re
import sys
import zipfile
from pathlib import Path

banner_id = os.environ["YANDEX_BANNER_ID"]
if not re.fullmatch(r"R-M-\d+-\d+", banner_id):
    raise SystemExit("Expected real Yandex R-M banner ID")
generated = list(Path("app/build/generated/source/buildConfig/release").rglob("BuildConfig.java"))
if len(generated) != 1:
    raise SystemExit("Expected exactly one generated release BuildConfig source")
config = generated[0].read_text()
if not re.search(r"boolean\s+ADS_TEST_MODE\s*=\s*false\s*;", config):
    raise SystemExit("Generated release BuildConfig does not disable ads test mode")
if not re.search(r"String\s+BANNER_ID\s*=\s*\"" + re.escape(banner_id) + r"\"\s*;", config):
    raise SystemExit("Generated release BuildConfig does not contain the own real banner ID")
if not re.search(r"boolean\s+DEBUG\s*=\s*false\s*;", config):
    raise SystemExit("Expected a non-debuggable release BuildConfig")
for file in sys.argv[1:]:
    with zipfile.ZipFile(file) as artifact:
        dex_files = [name for name in artifact.namelist() if name.endswith(".dex")]
        if not dex_files or not any(banner_id.encode("ascii") + b"\x00" in artifact.read(name) for name in dex_files):
            raise SystemExit(f"Own real R-M banner ID is absent from compiled DEX: {Path(file).name}")
    print(f"Compiled real banner ID confirmed: {Path(file).name}")
print("Generated release BuildConfig verified: ADS_TEST_MODE=false; DEBUG=false")
