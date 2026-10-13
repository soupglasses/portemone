#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

"""Exercise 16 KB checks against aligned and incompatible APK fixtures."""

import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "pages", Path(__file__).with_name("check-apk-pages.py")
)
pages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pages)


def elf(alignment=16384, address=0):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, address, 0, 120, 120, alignment)
    return bytes(data)


class PageSizeTests(unittest.TestCase):
    def archive(self, data, compressed=False, aligned=True):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "fixture.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            entry = zipfile.ZipInfo("lib/arm64-v8a/library.so")
            entry.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
            if aligned:
                offset = archive.fp.tell() + 30 + len(entry.filename)
                padding = (-offset) % pages.PAGE_SIZE
                if padding < 4:
                    padding += pages.PAGE_SIZE
                entry.extra = struct.pack("<HH", 0xFFFF, padding - 4) + bytes(padding - 4)
            archive.writestr(entry, data)
        return path

    def test_aligned_library_passes(self):
        self.assertEqual(pages.check_apk(self.archive(elf())), 1)

    def test_four_k_load_fails_even_when_zip_is_aligned(self):
        with self.assertRaisesRegex(ValueError, "LOAD alignment"):
            pages.check_apk(self.archive(elf(4096)))

    def test_unaligned_zip_fails_even_when_elf_is_aligned(self):
        with self.assertRaisesRegex(ValueError, "ZIP entry offset"):
            pages.check_apk(self.archive(elf(), aligned=False))

    def test_compressed_library_still_requires_aligned_loads(self):
        self.assertEqual(pages.check_apk(self.archive(elf(), compressed=True, aligned=False)), 1)
        with self.assertRaisesRegex(ValueError, "LOAD alignment"):
            pages.check_apk(self.archive(elf(4096), compressed=True))

    def test_mismatched_load_address_fails(self):
        with self.assertRaisesRegex(ValueError, "virtual address"):
            pages.check_apk(self.archive(elf(address=4096)))

    def test_truncated_library_fails(self):
        with self.assertRaises(ValueError):
            pages.check_apk(self.archive(b"\x7fELF\x02\x01"))


if __name__ == "__main__":
    unittest.main()
