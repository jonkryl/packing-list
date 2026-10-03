"""Check both artifacts use the persistent upload certificate, without disclosing secrets."""
import re
import sys
from pathlib import Path

actual_text = Path(sys.argv[1]).read_text()
actual = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", actual_text) or re.search(r"SHA256: ([0-9A-F:]+)", actual_text)
expected = re.search(r"SHA256: ([0-9A-F:]+)", Path(sys.argv[2]).read_text())
if not actual or not expected or actual[1].replace(":", "").lower() != expected[1].replace(":", "").lower():
    raise SystemExit("Artifact certificate does not match the protected upload key")
print("Artifact certificate matches the protected upload key")
