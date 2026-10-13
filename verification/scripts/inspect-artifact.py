"""Inspect a source-built release without loading server/NMS classes."""
import argparse
import hashlib
import json
import struct
import zipfile
import io
from collections import Counter
from pathlib import Path


def inspect(jar: Path) -> dict:
    failures = []
    versions = Counter()
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            failures.append("Duplicate archive entries")
        for name in names:
            if name.startswith("club/peacefulvanilla/superior/") or "/src/test/" in name:
                failures.append("Test observer or source packaged: " + name)
            if name.startswith(("net/minecraft/", "org/bukkit/", ".ai-control/")):
                failures.append("Server classes or private workspace packaged: " + name)
            if not name.endswith(".class"):
                continue
            header = archive.read(name)[:8]
            magic, _, major = struct.unpack(">IHH", header)
            if magic != 0xCAFEBABE:
                failures.append("Invalid class header: " + name)
            versions[major] += 1
            core = name.startswith("com/bgsoftware/superiorskyblock/")
            modern = name.startswith(("com/bgsoftware/superiorskyblock/nms/",
                                      "com/bgsoftware/superiorskyblock/platform/folia/",
                                      "com/bgsoftware/superiorskyblock/external/",
                                      "com/bgsoftware/superiorskyblock/libs/"))
            # Upstream embeds conditional addon hooks with their own Java
            # baselines, including its optional Java11 Nashorn provider.
            modern = modern or name == "com/bgsoftware/superiorskyblock/core/engine/OpenJdkNashornEngine.class"
            if core and not modern and major != 52:
                failures.append("Java8 core/API baseline changed: " + name + " major=" + str(major))
        descriptor = archive.read("plugin.yml").decode("utf-8")
        if "2026.3-pvc.1" not in descriptor or "folia-supported: true" not in descriptor:
            failures.append("Release descriptor mismatch")
        for version in ("v1_8_R3", "v1_12_R1", "v1_16_R3", "v1_17", "v1_18", "v1_19",
                        "v1_20_3", "v1_20_4", "v1_21", "v1_21_3", "v1_21_4", "v1_21_5",
                        "v1_21_7", "v1_21_9", "v1_21_10", "v26_1", "v26_2", "v26_3"):
            embedded = "nms/" + version
            if embedded not in names:
                failures.append("Original NMS adapter missing: " + version)
            else:
                with zipfile.ZipFile(io.BytesIO(archive.read(embedded))) as nms:
                    if not any(name.endswith("/NMSChunksImpl.class") for name in nms.namelist()):
                        failures.append("Invalid original NMS adapter: " + version)
        for version in ("v1_21_11", "v26_2"):
            expected = "com/bgsoftware/superiorskyblock/platform/folia/nms/" + version + "/RegionalChunkStorage.class"
            if expected not in names:
                failures.append("Modern storage adapter missing: " + version)
    return {"file": jar.name, "bytes": jar.stat().st_size,
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "classVersions": dict(sorted(versions.items())), "failures": failures, "passed": not failures}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    arguments = parser.parse_args()
    result = inspect(arguments.jar)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["passed"] else 1)
