#!/usr/bin/env python3
"""Reproduce the bundled source-only language artifacts from reviewed repository files."""
import argparse
import json
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
for language in ('java', 'typescript', 'rust'):
    directory = root / 'language-profiles' / language
    profile = (directory / 'profile.tsv').read_text()
    entry = next(row.split('\t')[2] for row in profile.splitlines() if row.startswith('grammar\t'))
    artifact = dict(schemaVersion=1, id='lang/' + language, version='0.1.0', entry=entry,
                    files={p.name: p.read_text() for p in sorted(directory.iterdir()) if p.suffix in ('.ubnf', '.tsv')}, dependencies={})
    content = json.dumps(artifact, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n'
    target = root / 'unlaxer-dsl/src/main/resources/ubnf-packages' / f'lang-{language}-0.1.0.json'
    if args.check:
        if target.read_text() != content:
            raise SystemExit(f'artifact differs: {target}')
    else:
        target.write_text(content)
