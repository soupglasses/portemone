#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

"""Reject APKs whose 64-bit native libraries cannot load on 16 KB devices."""

import argparse
import struct
import zipfile
from pathlib import Path

PAGE_SIZE = 16384


def check_elf(data):
    if data[:4] != b"\x7fELF":
        raise ValueError("native library is not ELF")
    if data[4] == 1:
        return False  # Android's 16 KB requirement applies to 64-bit libraries.
    if data[4:6] != b"\x02\x01":
        raise ValueError("unsupported ELF format")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or not count:
        raise ValueError("invalid ELF program headers")
    loads = 0
    for index in range(count):
        kind, _, file_offset, address, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset + index * entry_size
        )
        if kind == 1:  # PT_LOAD
            loads += 1
            if alignment < PAGE_SIZE or alignment & (alignment - 1):
                raise ValueError(f"LOAD alignment {alignment} is not 16 KB compatible")
            if file_offset % PAGE_SIZE != address % PAGE_SIZE:
                raise ValueError("LOAD file offset and virtual address disagree at 16 KB")
    if not loads:
        raise ValueError("ELF has no LOAD segments")
    return True


def check_apk(path):
    count = 0
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        if "AndroidManifest.xml" not in archive.namelist():
            raise ValueError("expected an APK with AndroidManifest.xml")
        for entry in archive.infolist():
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            try:
                if not check_elf(archive.read(entry)):
                    continue
                count += 1
                if entry.compress_type == zipfile.ZIP_STORED:
                    raw.seek(entry.header_offset + 26)
                    name_size, extra_size = struct.unpack("<HH", raw.read(4))
                    data_offset = entry.header_offset + 30 + name_size + extra_size
                    if data_offset % PAGE_SIZE:
                        raise ValueError(
                            f"uncompressed ZIP entry offset {data_offset} is not 16 KB aligned"
                        )
            except (ValueError, IndexError, struct.error) as error:
                raise ValueError(f"{entry.filename}: {error}") from error
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", type=Path, nargs="+")
    args = parser.parse_args()
    for path in args.apks:
        try:
            count = check_apk(path)
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            parser.exit(1, f"{path}: {error}\n")
        print(f"{path}: 16 KB ELF/ZIP checks passed ({count} 64-bit libraries)")


if __name__ == "__main__":
    main()
