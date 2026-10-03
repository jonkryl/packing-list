"""Verify every 64-bit native PT_LOAD segment in APK and AAB supports 16 KB pages."""
import struct
import sys
import zipfile

checked = 0
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name in sorted(archive.namelist()):
        if not name.endswith(".so") or not any(part in name.split("/") for part in ("arm64-v8a", "x86_64")):
            continue
        elf = archive.read(name)
        if len(elf) < 64 or elf[:4] != b"\x7fELF" or elf[4] != 2 or elf[5] not in (1, 2):
            raise SystemExit(f"Unexpected native ELF format: {name}")
        endian = "<" if elf[5] == 1 else ">"
        ph_offset = struct.unpack_from(endian + "Q", elf, 32)[0]
        ph_size, ph_count = struct.unpack_from(endian + "HH", elf, 54)
        if ph_size < 56 or ph_offset + ph_size * ph_count > len(elf):
            raise SystemExit(f"Invalid ELF program headers: {name}")
        for index in range(ph_count):
            offset = ph_offset + index * ph_size
            segment_type = struct.unpack_from(endian + "I", elf, offset)[0]
            if segment_type == 1:
                alignment = struct.unpack_from(endian + "Q", elf, offset + 48)[0]
                if alignment < 16_384:
                    raise SystemExit(f"{name}: PT_LOAD alignment is {alignment}, below 16384")
            if segment_type == 0x6474E552:  # PT_GNU_RELRO
                address = struct.unpack_from(endian + "Q", elf, offset + 16)[0]
                memory_size = struct.unpack_from(endian + "Q", elf, offset + 40)[0]
                if (address + memory_size) % 16_384 != 0:
                    raise SystemExit(f"{name}: GNU_RELRO end is not aligned to 16 KB")
        checked += 1
        print(f"16 KB compatible: {name}")
print(f"Verified {checked} 64-bit native libraries")
