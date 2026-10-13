"""Compare JVM-visible exported API descriptors against a pristine source build."""
import argparse
import io
import json
import struct
import zipfile
from pathlib import Path


def descriptors(data):
    stream = io.BytesIO(data)
    def u1(): return struct.unpack(">B", stream.read(1))[0]
    def u2(): return struct.unpack(">H", stream.read(2))[0]
    magic, _, major = struct.unpack(">IHH", stream.read(8))
    if magic != 0xCAFEBABE:
        raise ValueError("Invalid class")
    pool = [None] * u2()
    index = 1
    while index < len(pool):
        tag = u1()
        if tag == 1:
            # API names/descriptors use ASCII; modified UTF8 literals do not
            # participate in these contracts.
            pool[index] = stream.read(u2()).decode("utf-8", errors="replace")
        elif tag in (3, 4): stream.read(4)
        elif tag in (5, 6): stream.read(8); index += 1
        elif tag in (7, 8, 16, 19, 20): pool[index] = u2()
        elif tag in (9, 10, 11, 12, 17, 18): stream.read(4)
        elif tag == 15: stream.read(3)
        else: raise ValueError("Unsupported constant-pool tag " + str(tag))
        index += 1
    flags, this, superclass = u2(), u2(), u2()
    class_name = pool[pool[this]]
    parent = pool[pool[superclass]] if superclass else None
    interfaces = {pool[pool[u2()]] for _ in range(u2())}
    def attributes():
        for _ in range(u2()):
            u2()
            length = struct.unpack(">I", stream.read(4))[0]
            stream.read(length)
    members = {}
    for kind in ("field", "method"):
        for _ in range(u2()):
            access, name, signature = u2(), pool[u2()], pool[u2()]
            if access & 0x0005:  # public or protected
                members[(kind, name, signature)] = access
            attributes()
    return {"name": class_name, "major": major, "flags": flags,
            "parent": parent, "interfaces": interfaces, "members": members}


def compare(baseline: Path, current: Path):
    failures = []
    classes = 0
    with zipfile.ZipFile(current) as archive:
        for file in sorted(baseline.rglob("*.class")):
            old = descriptors(file.read_bytes())
            if not old["flags"] & 0x0001:
                continue
            classes += 1
            name = old["name"] + ".class"
            if name not in archive.namelist():
                failures.append("Removed public class: " + name); continue
            new = descriptors(archive.read(name))
            if new["major"] != 52:
                failures.append("API Java8 baseline changed: " + name)
            if old["parent"] != new["parent"] or not old["interfaces"].issubset(new["interfaces"]):
                failures.append("Changed inheritance: " + name)
            if not old["flags"] & 0x0010 and new["flags"] & 0x0010:
                failures.append("Class newly final: " + name)
            for member, access in old["members"].items():
                replacement = new["members"].get(member)
                if replacement is None:
                    failures.append("Removed exported member: " + name + " " + str(member))
                elif bool(access & 0x0008) != bool(replacement & 0x0008):
                    failures.append("Changed static contract: " + name + " " + str(member))
                elif access & 0x0001 and not replacement & 0x0001:
                    failures.append("Reduced public visibility: " + name + " " + str(member))
                elif member[0] == "method" and not access & 0x0400 and replacement & 0x0400:
                    failures.append("Method newly abstract: " + name + " " + str(member))
                elif member[0] == "method" and not access & 0x0010 and replacement & 0x0010:
                    failures.append("Method newly final: " + name + " " + str(member))
    return {"baselineExportedClasses": classes, "failures": failures, "passed": not failures,
            "scope": "Exported JVM class/member descriptors, inheritance and Java8 baseline; behavioral addon compatibility requires separate native qualification."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline_classes", type=Path)
    parser.add_argument("current_jar", type=Path)
    arguments = parser.parse_args()
    result = compare(arguments.baseline_classes, arguments.current_jar)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["passed"] else 1)
