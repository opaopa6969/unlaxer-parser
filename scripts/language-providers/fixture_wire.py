#!/usr/bin/env python3
"""Canonical requests and independent expected observations from the committed corpus."""
import json
from pathlib import Path
import sys

def hextext(value):
    return str(value).encode('utf-8').hex()

def wire(case):
    provider = case['provider']
    identity = {'javac': ('java','21.0.9','Java','CompilationUnit'), 'typescript': ('typescript','5.9.3','TypeScript','SourceFile'), 'rustc': ('rust','1.85.0','Rust','Crate')}[provider]
    language, version, grammar, entry = identity
    fields = [
        ['UNLAXER-PROVIDER','1'],
        ['request',hextext(case['name']),hextext(provider),hextext(case.get('version',version)),case['operation'],str(case['cursor']),str(case.get('execute',False)).lower()],
        ['project',hextext('project'),str(case['projectVersion'])],
        ['language',hextext(language),hextext('example'),hextext('1'),hextext(grammar),hextext(case.get('entry',entry)),hextext('root')],
        ['snapshot',hextext(case['uri']),str(case['snapshotVersion']),hextext(case['source'])],
    ]
    for document in sorted(case['documents'],key=lambda d:d['uri']):
        fields.append(['document',hextext(document['uri']),str(document['version']),hextext(document['text'])])
    for kind,key in [('config','configuration'),('parameter','parameters')]:
        for name,value in sorted(case[key].items()):
            fields.append([kind,hextext(name),hextext(value)])
    return '\n'.join('\t'.join(row) for row in fields)+'\nend\n'

def observation(case):
    diagnostics = sorted(f"{d['code']}@{d['uri']}:{d['start']}:{d['end']}:{d['severity']}" for d in case['diagnostics'])
    items = []
    for item in case['items']:
        locations = ','.join(f"{d['uri']}:{d['start']}:{d['end']}" for d in item['locations'])
        edits = ','.join(f"{d['uri']}:{d['start']}:{d['end']}:{hextext(d['text'])}" for d in item['edits'])
        items.append(f"{hextext(item['label'])}:{hextext(item['detail'])}[{locations}][{edits}]")
    return '\t'.join([case['name'],case['status'],','.join(diagnostics) or '-',','.join(items) or '-'])

if __name__ == '__main__':
    corpus = Path(sys.argv[1]); output = Path(sys.argv[2]); output.mkdir(parents=True,exist_ok=True)
    cases = json.loads(corpus.read_text())
    for case in cases:
        (output/(case['name']+'.wire')).write_text(wire(case))
    (output/'expected.tsv').write_text('\n'.join(observation(c) for c in cases)+'\n')
