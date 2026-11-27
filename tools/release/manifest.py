#!/usr/bin/env python3
"""Write the release manifest that `make sign` signs (contract: docs/research/auto-updater-2026-11-10.md §API).

usage: manifest.py <jar> <version> <out>

The manifest is signed as exact bytes and never re-serialized, so the layout here is free; it is
compact and in a fixed key order so the same jar always yields the same bytes.
"""
import hashlib
import json
import os
import re
import sys
import zipfile


def fail(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) != 4:
        fail("usage: manifest.py <jar> <version> <out>")
    jar, version, out = sys.argv[1:]

    with zipfile.ZipFile(jar) as z:
        mod = json.loads(z.read("fabric.mod.json"))
    if mod.get("id") != "soul":
        fail(f"{jar} is mod '{mod.get('id')}', not soul")
    if mod.get("version") != version:
        fail(f"{jar} says version {mod.get('version')}, expected {version}")
    if re.search(r"(^|[.-])dev$", version):
        fail(f"{version} is a local build name (.dev); releases never carry it")
    depends = mod.get("depends") or {}
    minecraft = depends.get("minecraft")
    if not isinstance(minecraft, str):
        fail("depends.minecraft must be a single string predicate")

    channel = "beta" if re.match(r"^[0-9]+\.[0-9]+\.[0-9]+-", version) else "stable"
    with open(jar, "rb") as f:
        sha256 = hashlib.sha256(f.read()).hexdigest()

    manifest = {
        "mod": "soul",
        "version": version,
        "channel": channel,
        "minecraft": minecraft,
        "depends": depends,
        "file": f"soul-{version}.jar",
        "size": os.path.getsize(jar),
        "sha256": sha256,
    }
    with open(out, "wb") as f:
        f.write(json.dumps(manifest, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


if __name__ == "__main__":
    main()
