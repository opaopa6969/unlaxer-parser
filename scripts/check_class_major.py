#!/usr/bin/env python3
"""Require that every .class file in the given jars has one class-file major version.

Usage: check_class_major.py <major> <jar> [<jar> ...]
  61 = Java 17 (the *-jdk17 artifacts), 65 = Java 21 (the main artifacts). #311
"""

import struct
import sys
import zipfile


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    expected = int(argv[1])
    bad = []
    for jar in argv[2:]:
        count = 0
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if not name.endswith(".class"):
                    continue
                count += 1
                major = struct.unpack(">H", archive.read(name)[6:8])[0]
                if major != expected:
                    bad.append(f"{jar}!{name}: major {major}")
        if count == 0:
            bad.append(f"{jar}: no class files")
        print(f"{jar}: {count} classes, expected major {expected}")
    if bad:
        print("\n".join(bad[:50]), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
