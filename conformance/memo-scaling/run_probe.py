#!/usr/bin/env python3
"""Read-only reproduction against an already compiled source tree and its Surefire classpath."""
import argparse
import hashlib
import re
import textwrap
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--tree', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--budget-bytes', type=int)
parser.add_argument('--eviction', choices=['true', 'false'])
parser.add_argument('--scales', nargs='+', type=int, default=[1, 4, 16, 64, 256])
args = parser.parse_args()
tree = args.tree.resolve()
if any(scale <= 0 or scale > 256 for scale in args.scales):
    parser.error('scales must be in 1..256')
if args.budget_bytes is not None and not 0 <= args.budget_bytes <= 1024**3:
    parser.error('budget must be in 0..1GiB')
reports = sorted((tree / 'unlaxer-dsl/target/surefire-reports').glob('TEST-*.xml'))
if not reports:
    parser.error('first run a DSL test (even the known baseline RED) to obtain its classpath')
properties = ET.parse(reports[0]).getroot().find('properties')
classpath = next(p.get('value') for p in properties if p.get('name') == 'java.class.path')
java_home = Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None
java = str(java_home / 'bin/java') if java_home else 'java'
javac = str(java_home / 'bin/javac') if java_home else 'javac'
options = []
if args.budget_bytes is not None:
    options.append(f'-Dunlaxer.memo.success.maxBytes={args.budget_bytes}')
if args.eviction is not None:
    options.append(f'-Dunlaxer.memo.evictBelowFrontier={args.eviction}')
grammar_file = tree / 'conformance/memo-scaling/scale.ubnf'
if grammar_file.exists():
    grammar = grammar_file.read_bytes()
else:
    test = (tree / 'unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/ParserScalingSmokeTest.java').read_text()
    block = re.search(r'GRAMMAR = """\n(.*?)"""\.formatted\(PACKAGE\)', test, re.S)
    package = re.search(r'PACKAGE = "([^"]+)"', test).group(1)
    grammar = (textwrap.dedent(block.group(1)).strip().replace('%s', package) + '\n').encode()
args.output.mkdir(parents=True, exist_ok=False)
with tempfile.TemporaryDirectory(prefix='scaling-probe-') as classes:
    subprocess.run([javac, '-cp', classpath, '-d', classes,
                    str(Path(__file__).with_name('ScalingProbe.java'))], check=True)
    command = [java, '-Xms64m', '-Xmx1g', *options, '-cp', classes + os.pathsep + classpath,
               'ScalingProbe', *map(str, args.scales)]
    metadata = {
        'grammar_sha256': hashlib.sha256(grammar).hexdigest(),
        'input_sha256': {str(scale): hashlib.sha256(('let abc = 1+2*3-(4+5)/6; ' * (16 * scale)).encode()).hexdigest() for scale in args.scales},
        'dirty': bool(subprocess.check_output(['git', '-C', str(tree), 'status', '--porcelain'], text=True).strip()),
        'revision': subprocess.check_output(['git', '-C', str(tree), 'rev-parse', 'HEAD'], text=True).strip(),
        'java': subprocess.check_output([java, '-version'], stderr=subprocess.STDOUT, text=True),
        'command': command,
        'warmup': 'one same-input parse before each measured operation; fresh ParseContext each time',
        'scope': 'parse allocation/time; generation and javac excluded; shared host, not a production benchmark',
    }
    (args.output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    with (args.output / 'probe.tsv').open('w') as output, (args.output / 'stderr.log').open('w') as error:
        subprocess.run(command, cwd=tree / 'unlaxer-dsl', stdout=output, stderr=error, check=True)
