#!/usr/bin/env python3
"""Reproduce legacy JDK fold deltas; prints TSV and never updates the oracle."""
import argparse
import hashlib
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("unicode_data", type=Path)
parser.add_argument("jdk_dump", type=Path)
args = parser.parse_args()
payload = args.unicode_data.read_bytes()
if hashlib.sha256(payload).hexdigest() != "2e1efc1dcb59c575eedf5ccae60f95229f706ee6d031835247d843c11d96470c":
    raise SystemExit("expected the official UnicodeData 17.0.0 payload")
unicode = {}
names = {}
for line in payload.decode("utf-8").splitlines():
    fields = line.split(";")
    point = int(fields[0], 16)
    names[point] = fields[1]
    unicode[point] = (
        int(fields[12], 16) if fields[12] else point,
        int(fields[13], 16) if fields[13] else point,
    )
legacy = {}
for line in args.jdk_dump.read_text().splitlines():
    point, upper, lower, fold = [int(value, 16) for value in line.split(";")]
    legacy[point] = fold
for point in range(0x110000):
    if 0xD800 <= point <= 0xDFFF:
        continue
    upper = unicode.get(point, (point, point))[0]
    pinned = unicode.get(upper, (upper, upper))[1]
    old = legacy.get(point, point)
    if old != pinned:
        print(f"{point:06X}\t{old:06X}\t{pinned:06X}\t{names.get(point, '')}")
