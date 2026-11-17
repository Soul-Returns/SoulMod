#!/usr/bin/env python3
"""Compare two builds of the mod jar by content, ignoring how they were compressed.

usage: compare-jars.py <a.jar> <b.jar>

Two machines produce different jar bytes from the same source (the deflate implementation
differs), so hashes of independently built jars never match. The unpacked files do. Nested jars
are compared the same way. Exits 0 when the content is identical, 1 with a list otherwise.
"""
import hashlib
import io
import sys
import zipfile


def digest(archive: zipfile.ZipFile, prefix: str = "") -> dict[str, str]:
    entries = {}
    for info in archive.infolist():
        if info.is_dir():
            continue
        data = archive.read(info)
        name = prefix + info.filename
        if info.filename.endswith(".jar"):
            entries.update(digest(zipfile.ZipFile(io.BytesIO(data)), name + "!/"))
        else:
            entries[name] = hashlib.sha256(data).hexdigest()
    return entries


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    a = digest(zipfile.ZipFile(sys.argv[1]))
    b = digest(zipfile.ZipFile(sys.argv[2]))
    different = sorted(n for n in a.keys() & b.keys() if a[n] != b[n])
    only_a = sorted(a.keys() - b.keys())
    only_b = sorted(b.keys() - a.keys())
    if not (different or only_a or only_b):
        print(f"identical content: {len(a)} files")
        return 0
    for label, names in (("differs", different), ("only in first", only_a), ("only in second", only_b)):
        for name in names[:50]:
            print(f"{label}: {name}")
    print(f"{len(different)} differ, {len(only_a)} only in first, {len(only_b)} only in second")
    return 1


if __name__ == "__main__":
    sys.exit(main())
