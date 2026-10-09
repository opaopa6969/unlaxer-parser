#!/usr/bin/env python3
"""Analysis-only TypeScript / rustc adapters for UNLAXER-PROVIDER 1."""
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

LIMIT = 512 * 1024

def unhex(value):
    return bytes.fromhex(value).decode('utf-8')

def hextext(value):
    return str(value).encode('utf-8').hex()

def request():
    raw = sys.stdin.buffer.read(LIMIT + 5)
    if len(raw) > LIMIT + 4 or not raw.endswith(b'end\n'):
        raise ValueError('invalid frame')
    lines = raw.decode('ascii').splitlines()
    if len(lines) < 6 or lines[0] != 'UNLAXER-PROVIDER\t1' or lines[-1] != 'end':
        raise ValueError('unsupported protocol')
    header = lines[1].split('\t')
    project = lines[2].split('\t')
    if len(header) != 7 or header[0] != 'request' or header[6] not in ('true', 'false') or len(project) != 3 or project[0] != 'project':
        raise ValueError('invalid header')
    result = dict(id=unhex(header[1]), provider=unhex(header[2]), version=unhex(header[3]), operation=header[4], cursor=int(header[5]), execute=header[6] == 'true', project=unhex(project[1]), projectVersion=int(project[2]), documents={}, config={}, parameters={}, echo=raw[:-4].hex())
    language = lines[3].split('\t')
    if len(language) != 7 or language[0] != 'language':
        raise ValueError('invalid language identity')
    result['language'] = dict(zip(('id', 'package', 'version', 'grammar', 'entry', 'region'), map(unhex, language[1:])))
    for index, line in enumerate(lines[4:-1]):
        row = line.split('\t')
        if row[0] in ('snapshot', 'document') and len(row) == 4:
            value = dict(uri=unhex(row[1]), version=int(row[2]), text=unhex(row[3]))
            if not 0 <= value['version'] <= 9223372036854775807:
                raise ValueError('negative version')
            if row[0] == 'snapshot':
                if index != 0:
                    raise ValueError('duplicate snapshot')
                result['snapshot'] = value
            elif value['uri'] in result['documents']:
                raise ValueError('duplicate document')
            else:
                result['documents'][value['uri']] = value
        elif row[0] in ('config', 'parameter') and len(row) == 3:
            values = result['config' if row[0] == 'config' else 'parameters']
            key = unhex(row[1])
            if key in values:
                raise ValueError('duplicate field')
            values[key] = unhex(row[2])
        else:
            raise ValueError('unknown field')
    if not 0 <= result['projectVersion'] <= 9223372036854775807 or any(not value for value in result['language'].values()) or not all(result[k] for k in ('id', 'provider', 'version', 'project')):
        raise ValueError('invalid identity/version')
    if not 0 <= result['cursor'] <= len(result['snapshot']['text']):
        raise ValueError('invalid CP cursor')
    if result['snapshot']['uri'] in result['documents'] and result['documents'][result['snapshot']['uri']] != result['snapshot']:
        raise ValueError('snapshot collision')
    return result

def response(req, value):
    lines = ['UNLAXER-PROVIDER\t1', '\t'.join(['response', hextext(req['id']), hextext(req['provider']), hextext(req['version']), req['echo'], hextext(req['project']), str(req['projectVersion']), value['status']])]
    lines += ['capability\t' + c for c in sorted(value.get('capabilities', []))]
    def location(loc):
        return '\t'.join([hextext(loc['uri']), str(loc['version']), str(loc['start']), str(loc['end'])])
    for index, diagnostic in enumerate(value.get('diagnostics', [])):
        if not diagnostic['locations']:
            raise ValueError('diagnostic requires an origin')
        for origin, loc in enumerate(diagnostic['locations']):
            fields = ['diagnostic', hextext(diagnostic['code']), hextext(diagnostic['message']), diagnostic['severity']] if origin == 0 else ['origin', str(index)]
            lines.append('\t'.join(fields + [location(loc)]))
    for index, item in enumerate(value.get('items', [])):
        lines.append('\t'.join(['item', hextext(item['label']), hextext(item['detail'])]))
        for loc in item.get('locations', []):
            lines.append('\t'.join(['location', str(index), location(loc)]))
        for edit in item.get('edits', []):
            lines.append('\t'.join(['edit', str(index), location(edit['location']), hextext(edit['text'])]))
    wire = '\n'.join(lines + ['end', ''])
    if len(wire) > 4 * 1024 * 1024:
        raise ValueError('response limit')
    return wire

RUST_CAPS = ['TYPE_CHECK', 'OWNERSHIP', 'LIFETIME', 'MATCH_EXHAUSTIVENESS', 'MACRO_DIAGNOSTIC_ORIGINS']

def empty(status, capabilities):
    return dict(status=status, capabilities=capabilities, diagnostics=[], items=[])

def rust(req, executable, deadline):
    def run(arguments, **kwargs):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise subprocess.TimeoutExpired(arguments, 0)
        return subprocess.run(arguments, text=True, capture_output=True, timeout=remaining, **kwargs)
    if req['provider'] != 'rustc' or req['version'] != '1.85.0':
        return empty('UNAVAILABLE', RUST_CAPS)
    version = run([executable, '--version'])
    if version.returncode or version.stdout.split()[:2] != ['rustc', '1.85.0']:
        return empty('UNAVAILABLE', RUST_CAPS)
    if req['language']['id'] != 'rust' or req['language']['entry'] != 'Crate' or req['operation'] != 'VALIDATE' or req['execute'] or set(req['config']) - {'edition'} or set(req['parameters']) - {'timeoutMs'}:
        return empty('UNSUPPORTED', RUST_CAPS)
    edition = req['config'].get('edition', '2021')
    if edition not in ('2018', '2021', '2024'):
        return empty('UNSUPPORTED', RUST_CAPS)
    snapshots = [req['snapshot']] + [d for d in req['documents'].values() if d != req['snapshot'] and d['uri'].endswith('.rs')]
    # The compiler may expand declarative macros, but cannot read arbitrary include/env inputs.
    forbidden = re.compile(r'\b(?:include|include_str|include_bytes|env|option_env|path)\b')
    if any(forbidden.search(d['text']) for d in snapshots):
        return empty('UNSUPPORTED', RUST_CAPS)
    with tempfile.TemporaryDirectory(prefix='unlaxer-rust-provider-') as temporary:
        root = Path(temporary)
        files = {}
        for index, snapshot in enumerate(snapshots):
            from urllib.parse import urlsplit, unquote
            name = 'main.rs' if index == 0 else Path(unquote(urlsplit(snapshot['uri']).path)).name
            if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*\.rs', name) or str(root / name) in files:
                return empty('UNSUPPORTED', RUST_CAPS)
            path = root / name
            path.write_text(snapshot['text'], encoding='utf-8')
            files[str(path)] = snapshot
        output = run([executable, '--crate-name', 'unlaxer_analysis', '--crate-type', 'lib', '--edition', edition, '--emit=metadata', '--error-format=json', '--out-dir', str(root), str(root / 'main.rs')], cwd=root)
        diagnostics = []
        for line in output.stderr.splitlines():
            try:
                diagnostic = json.loads(line)
            except json.JSONDecodeError:
                return empty('FAILED', RUST_CAPS)
            if diagnostic.get('$message_type') != 'diagnostic' or diagnostic.get('level') not in ('error', 'warning'):
                continue
            origins = []
            def add(span):
                candidate = Path(span['file_name'])
                snapshot = files.get(str(candidate.resolve() if candidate.is_absolute() else (root / candidate).resolve()))
                if snapshot is None:
                    return
                encoded = snapshot['text'].encode('utf-8')
                if not 0 <= span['byte_start'] <= span['byte_end'] <= len(encoded):
                    raise ValueError('invalid compiler range')
                start = len(encoded[:span['byte_start']].decode('utf-8'))
                end = len(encoded[:span['byte_end']].decode('utf-8'))
                loc = dict(uri=snapshot['uri'], version=snapshot['version'], start=start, end=end)
                if loc not in origins:
                    origins.append(loc)
                expansion = span.get('expansion')
                if expansion:
                    add(expansion['span'])
            for span in diagnostic.get('spans', []):
                if span['is_primary']:
                    add(span)
            if origins:
                diagnostics.append(dict(code=(diagnostic.get('code') or {}).get('code', 'RUST-WARNING'), message=diagnostic['message'], severity=diagnostic['level'].upper(), locations=origins))
        if output.returncode and not diagnostics:
            return empty('FAILED', RUST_CAPS)
        return dict(status='DIAGNOSTICS' if diagnostics else 'OK', capabilities=RUST_CAPS, diagnostics=diagnostics, items=[])

def main():
    req = request()
    timeout = int(req['parameters'].get('timeoutMs', '10000'))
    if not 1 <= timeout <= 60000:
        raise ValueError('invalid timeout')
    language = sys.argv[1]
    caps = RUST_CAPS if language == 'rust' else ['PARSE', 'TYPE_CHECK', 'FLOW_NARROWING', 'HOVER', 'DEFINITION', 'COMPLETION']
    try:
        if language == 'rust':
            value = rust(req, sys.argv[2], time.monotonic() + timeout / 1000)
        elif language == 'typescript':
            payload = dict(req, snapshot=dict(req['snapshot'], version=str(req['snapshot']['version'])), documents={uri: dict(d, version=str(d['version'])) for uri, d in req['documents'].items()})
            output = subprocess.run(['node', str(Path(__file__).with_name('typescript.cjs')), sys.argv[2]], input=json.dumps(payload), text=True, capture_output=True, timeout=timeout / 1000)
            value = json.loads(output.stdout) if output.returncode == 0 else empty('FAILED', caps)
        else:
            raise ValueError('unknown adapter')
    except FileNotFoundError:
        value = empty('UNAVAILABLE', caps)
    except subprocess.TimeoutExpired:
        value = empty('TIMEOUT', caps)
    sys.stdout.write(response(req, value))

if __name__ == '__main__':
    main()
