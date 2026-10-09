#!/usr/bin/env python3
"""Deterministic grammar mutations, Unicode boundaries and deep expressions (seed 384383)."""
from pathlib import Path
import argparse
parser = argparse.ArgumentParser()
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent / 'language-profiles'
seed = 384383
for language in ('java', 'typescript', 'rust'):
    rows = []
    for step in range(32):
        seed = (1664525 * seed + 1013904223) & 0xffffffff
        value = seed % 1000
        text = ('日😀', 'e\u0301', '𐐀', '日本語')[seed % 4]
        prefix = f'// {step} {text}\r\n'
        entry, source = {
            'java': ('CompilationUnit', f'class Main {{ int value() {{ return {value}; }} }}'),
            'typescript': ('SourceFile', f'function value(): number {{ return {value}; }}'),
            'rust': ('Crate', f'fn value() -> i32 {{ {value} }}'),
        }[language]
        for suffix, original, accepted in [('original', source, True), ('remove-close', source[:-1], False)]:
            source_text = prefix + original
            rows.append('\t'.join([f'seed-384383-{step}-{suffix}', entry, source_text.encode().hex(), str(accepted).lower(), str(len(source_text)), 'GENERATED']))
    for depth in (1, 8, 32, 64):
        text = '(' * depth + '1' + ')' * depth
        rows.append('\t'.join([f'depth-{depth}', 'Expression', text.encode().hex(), 'true', str(len(text)), 'SKIP']))
    content = '\n'.join(rows) + '\n'
    target = root / language / 'mutations.tsv'
    if args.check:
        if target.read_text() != content:
            raise SystemExit(f'corpus differs: {target}; run python3 scripts/language-profile-corpus.py')
    else:
        target.write_text(content)
