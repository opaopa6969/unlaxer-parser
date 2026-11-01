#!/usr/bin/env python3
"""lsp4j 0.23.1 sources JAR の DTO 宣言から authoring 境界の型表を生成する。

使い方: python3 scripts/generate-lsp4j-dto-schema.py /path/to/org.eclipse.lsp4j-0.23.1-sources.jar
サーバー出力や fixture は読まない。継承した private field も含める。
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES_SHA256 = '98b0c16e84fc4188043ab2e18c5bbbfa0fbd0aacfb47ef70b47e8f07242e28da'
REQUESTS = {
    'textDocument/hover': 'HoverParams',
    'textDocument/completion': 'CompletionParams',
    'textDocument/definition': 'DefinitionParams',
    'textDocument/references': 'ReferenceParams',
    'textDocument/rename': 'RenameParams',
    'textDocument/documentSymbol': 'DocumentSymbolParams',
    'textDocument/foldingRange': 'FoldingRangeRequestParams',
    'textDocument/linkedEditingRange': 'LinkedEditingRangeParams',
    'textDocument/codeAction': 'CodeActionParams',
    'textDocument/signatureHelp': 'SignatureHelpParams',
    'textDocument/semanticTokens/full': 'SemanticTokensParams',
}


def generate(jar):
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if digest != SOURCES_SHA256:
        raise ValueError('lsp4j 0.23.1 sources JAR の SHA-256 が一致しません')
    with zipfile.ZipFile(jar) as archive:
        sources = {Path(n).stem: archive.read(n).decode() for n in archive.namelist()
                   if n.startswith('org/eclipse/lsp4j/') and n.endswith('.java')}
    types = {}

    def describe(raw):
        raw = raw.strip().removeprefix('? extends ')
        if raw == 'Tuple.Two<Integer, Integer>':
            return ['tuple', 'integer', 'integer']
        if raw.startswith('List<'):
            return ['list', describe(raw[5:-1])]
        if raw.startswith('Either<'):
            # Split only the outer generic arguments.
            parts, depth, start = [], 0, 7
            for i, char in enumerate(raw[7:-1], 7):
                depth += (char == '<') - (char == '>')
                if char == ',' and depth == 0:
                    parts.append(describe(raw[start:i]))
                    start = i + 1
            return ['either', *parts, describe(raw[start:-1])]
        scalar = {'String': 'string', 'Boolean': 'boolean', 'boolean': 'boolean',
                  'Integer': 'integer', 'int': 'integer', 'Object': 'any'}
        if raw in scalar:
            return scalar[raw]
        source = sources[raw]
        if re.search(r'public enum ' + raw + r'\b', source):
            return 'enum'
        if raw not in types:
            types[raw] = {}  # recursion guard
            declaration = re.search(r'public (?:abstract )?class ' + raw + r'(?: extends (\w+))?', source)
            assert declaration, raw
            if declaration[1]:
                describe(declaration[1])
                types[raw].update(types[declaration[1]])
            for field_type, name in re.findall(r'^  private ([^;=\n]+?) (\w+);', source, re.M):
                types[raw][name] = 'workDoneToken' if name == 'workDoneToken' else describe(field_type)
        return raw

    def has_work_done(name):
        source = sources[name]
        if re.search(r'private Either<String, Integer> workDoneToken;', source):
            return True
        parent = re.search(r'public (?:abstract )?class ' + name + r' extends (\w+)', source)
        return bool(parent and has_work_done(parent[1]))

    work_done = []
    for service, segment in [('TextDocumentService', 'textDocument'), ('WorkspaceService', 'workspace')]:
        source = sources[service]
        for annotation, name, param in re.findall(
                r'@JsonRequest(\([^\n]*\))?\s+(?:@[^\n]+\s+)*default CompletableFuture<[^\n]+> (\w+)\((\w+) \w+\)', source):
            explicit = re.search(r'(?:value\s*=\s*)?"([^"]+)"', annotation)
            method = explicit[1] if explicit else name
            if 'useSegment = false' not in annotation:
                method = segment + '/' + method
            if has_work_done(param):
                work_done.append(method)

    describe('ClientCapabilities')
    for name in REQUESTS.values():
        describe(name)
    return {'lsp4jVersion': '0.23.1', 'sourcesSha256': digest,
            'requests': dict(sorted(REQUESTS.items())), 'workDoneMethods': sorted(work_done),
            'types': {name: dict(sorted(fields.items())) for name, fields in sorted(types.items())}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sources', type=Path)
    args = parser.parse_args()
    output = ROOT / 'rust/unlaxer-ubnf-lsp/src/lsp4j-dto-schema.json'
    output.write_text(json.dumps(generate(args.sources), ensure_ascii=False, indent=2) + '\n')
