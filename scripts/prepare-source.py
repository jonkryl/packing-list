"""Prepare a deterministic, secret-free source archive for the GitHub connector transport."""
import gzip
import hashlib
import io
import json
import tarfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
destination = root.parent
excluded_parts = {".git", ".gradle", ".idea", "build", "ci-artifacts", "ci-apks", "release-output", "__pycache__"}
excluded_names = {"local.properties", ".DS_Store", "source-bootstrap.tar.gz"}
excluded_suffixes = {".jks", ".keystore", ".p12", ".pem", ".key", ".hprof", ".pyc"}
files = [p for p in root.rglob("*") if p.is_file()
         and not (set(p.relative_to(root).parts) & excluded_parts)
         and p.name not in excluded_names and p.suffix not in excluded_suffixes]
buffer = io.BytesIO()
with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as archive:
    for file in sorted(files, key=lambda p: p.relative_to(root).as_posix()):
        if file.is_symlink():
            raise SystemExit(f"Source symlinks are forbidden: {file.relative_to(root)}")
        content = file.read_bytes()
        info = tarfile.TarInfo(file.relative_to(root).as_posix())
        info.size = len(content)
        info.mode = 0o755 if file.name == "gradlew" or file.suffix == ".sh" else 0o644
        info.mtime = 0
        archive.addfile(info, io.BytesIO(content))
archive_path = destination / "source-bootstrap.tar.gz"
with archive_path.open("wb") as output:
    with gzip.GzipFile(filename="", fileobj=output, mode="wb", mtime=0) as zipped:
        zipped.write(buffer.getvalue())
digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
template = (root / "scripts/bootstrap-source.template.yml").read_text()
workflow_path = destination / "bootstrap-source-ready.yml"
workflow_path.write_text(template.replace("SOURCE_SHA_PLACEHOLDER", digest))
record = {"sha256": digest, "fileCount": len(files), "archiveBytes": archive_path.stat().st_size,
          "archive": str(archive_path), "workflow": str(workflow_path)}
(destination / "source-bootstrap-manifest.json").write_text(json.dumps(record, indent=2) + "\n")
print(json.dumps(record))
