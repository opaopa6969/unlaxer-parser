#!/usr/bin/env python3
"""Run public contract checks, optionally all four pinned engine routes.

Private code/generated output stays under ignored target/. No network fetch or
production engine modification. Timings/heap/allocation are not benchmarks.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
from oracle import oracle

ROOT = Path(__file__).resolve().parents[2]
FIX = ROOT / 'docs/fixtures/engine-reuse'
EX = Path(__file__).resolve().parent
OUT = ROOT / 'target/engine-reuse'
CLASSIC_PIN = '875144631d932d7fe31e66a3b68045ecaf77928e'
UBNFC_PIN = '95fd2a54e0769949d88f5be11d86eeae66a1ce25'
ENV = dict(os.environ, CARGO_INCREMENTAL='0')


def run(args, cwd=ROOT, timeout=300):
    process = subprocess.run(list(map(str, args)), cwd=cwd, env=ENV,
                             text=True, encoding='utf-8', capture_output=True, timeout=timeout)
    if process.returncode:
        raise RuntimeError(f'{args[0]} exit {process.returncode}\n{process.stdout}\n{process.stderr}')
    return process.stdout


def rust(*args):
    return ['rustup', 'run', '1.85.0', *args]


def wrapper(name, command):
    path = OUT / name
    path.write_text('#!/bin/sh\nexec ' + shlex.join(list(map(str, command))) + ' "$@"\n')
    path.chmod(0o700)
    return path


def compile_probes(private):
    cp = (OUT/'classpath').read_text().strip()
    classes = OUT/'classes'
    for name in ['Classic'] + (['Ubnfc'] if private else []):
        generated = OUT/('classic-java' if name == 'Classic' else 'ubnfc-java')
        run(['javac', '-cp', cp+os.pathsep+str(classes), '-d', classes,
             *sorted(generated.rglob('*.java')), EX/'Canonical.java', EX/(name+'Probe.java')])
    run(['javac', '-d', classes, EX/'Policy.java'])
    run(rust('rustc', '--edition=2021', '-A', 'warnings', '--crate-name=unlaxer_runtime',
             '--crate-type=rlib', ROOT/'rust/unlaxer-runtime/src/lib.rs', '-o', OUT/'libunlaxer_runtime.rlib'))
    for name in ['classic']+(['ubnfc'] if private else []):
        args = rust('rustc', '--edition=2021', '-A', 'warnings', EX/(name+'_probe.rs'), '-o', OUT/(name+'-rust-probe'))
        if name == 'classic': args += ['--extern', 'unlaxer_runtime='+str(OUT/'libunlaxer_runtime.rlib')]
        run(args)
    for name in ['driver', 'policy']:
        run(rust('rustc', '--edition=2021', '-A', 'warnings', EX/(name+'.rs'), '-o', OUT/name))
    commands = {
        'classic-java': wrapper('classic-java-probe', ['java', '-Xmx256m', '-cp', str(classes)+os.pathsep+cp, 'ClassicProbe']),
        'classic-rust': OUT/'classic-rust-probe'
    }
    if private:
        commands['ubnfc-java'] = wrapper('ubnfc-java-probe', ['java', '-Xmx256m', '-cp', classes, 'UbnfcProbe'])
        commands['ubnfc-rust'] = OUT/'ubnfc-rust-probe'
    return commands


def build(private):
    # Resolve the classpath in the same reactor; a clean CI runner has no locally
    # installed unlaxer-common SNAPSHOT and must not download one instead.
    run(['mvn', '-B', '-pl', 'unlaxer-common,unlaxer-dsl', '-am', '-DskipTests', 'test-compile',
         'dependency:build-classpath', '-Dmdep.outputFile='+str(OUT/'classpath')], timeout=600)
    cp = os.pathsep.join([str(ROOT/'unlaxer-common/target/classes'), str(ROOT/'unlaxer-dsl/target/classes'), (OUT/'classpath').read_text().strip()])
    (OUT/'classpath').write_text(cp)
    run(['java', '-cp', cp, 'org.unlaxer.dsl.CodegenMain', '--grammar', FIX/'Reuse.ubnf',
         '--output', OUT/'classic-java', '--generators', 'AST,Parser,Mapper'])
    run(rust('cargo', 'build', '--locked', '--manifest-path', ROOT/'rust/Cargo.toml', '-p', 'unlaxer-generator', '--target-dir', OUT/'cargo'), timeout=600)
    if (OUT/'classic-rust').exists(): shutil.rmtree(OUT/'classic-rust')
    run([OUT/'cargo/debug/unlaxer', 'generate', '--grammar', FIX/'Reuse.ubnf', '--output', OUT/'classic-rust'])
    if private:
        run(rust('cargo', 'build', '--locked', '-p', 'ubnfc-front', '-p', 'ubnfc-rust', '--target-dir', private/'target'), private, timeout=600)
        ENV['UBNFC_RUST_BIN']=str(private/'target/debug/ubnfc-rust')
        ENV['UBNFC_JAVA_JAR']=str(private/'ubnfc-java/target/ubnfc-java-0.1.0-SNAPSHOT.jar')
        run(['mvn', '-B', '-f', private/'ubnfc-java/pom.xml', '-DskipTests', 'package'], private, timeout=600)
        for language in ['java','rust']:
            directory = OUT/('ubnfc-'+language)
            if directory.exists(): shutil.rmtree(directory)
            run([private/'target/debug/ubnfc', 'gen', '--grammar', FIX/'Reuse.ubnf', '--target', language, '--out', directory], private)


def contract_checks():
    counts = {}
    for kind, filename, expected_columns in [('proof','proofs.tsv',[0,2,3]), ('capability','capabilities.tsv',[0,5,6])]:
        rows = [line.split('\t') for line in (FIX/filename).read_text().splitlines() if line and not line.startswith('#')]
        expected = ''.join('\t'.join(p[i] for i in expected_columns)+'\n' for p in rows)
        for language, command in [('java',['java','-cp',OUT/'classes','Policy']),('rust',[OUT/'policy'])]:
            actual=run([*command,kind,FIX/filename])
            (OUT/f'{kind}-{language}.tsv').write_text(actual)
            if actual!=expected: raise AssertionError(f'{kind} {language}\n{actual}\nEXPECTED\n{expected}')
        counts[kind] = len(rows)
    return counts


def correctness(commands):
    expected_rows, observed_rows = [], []
    for case in json.loads((FIX/'cases.json').read_text()):
        source=case['source']; expected, required=oracle(source, with_expected=True)
        expected_rows.append(case['id']+'\t'+expected)
        path=OUT/'input.txt'; path.write_bytes(source.encode())
        for name, command in commands.items():
            actual=run([command,path]).strip('\n')
            observed_rows.append(case['id']+'\t'+name+'\t'+actual)
            if expected.startswith('ACCEPT\t'):
                if actual!=expected: raise AssertionError(f"{case['id']} {name}\n{actual}\n{expected}")
            elif actual.split('\t')[:2]!=expected.split('\t'):
                raise AssertionError(f"{case['id']} {name}: {actual} != {expected}")
            if required is not None:
                hints=bytes.fromhex(actual.split('\t')[3]).decode().split('|')
                if required not in [hint[1:-1] if hint.startswith("'") and hint.endswith("'") else hint for hint in hints]:
                    raise AssertionError(f"{case['id']} {name}: missing independently expected {required!r}: {hints}")
    (OUT/'oracle.tsv').write_text('\n'.join(expected_rows)+'\n')
    (OUT/'correctness.tsv').write_text('\n'.join(observed_rows)+'\n')
    return len(expected_rows)


def driver_checks(commands, grammar):
    rows=[]
    source=OUT/'driver-input.txt'; expected_file=OUT/'expected.txt'
    def execute(name, text, lean, rich, purity='PURE', deadline=5000, limit=4096):
        source.write_bytes(text.encode()); expected_file.write_text(oracle(text)+'\n')
        actual=run([OUT/'driver',source,grammar,'snapshot-1',purity,lean,rich,expected_file,deadline,limit],timeout=35).strip('\n')
        rows.append(name+'\t'+actual)
        return actual
    # Native failure routing and native AST projection, not mocked engines.
    for language in ['java','rust']:
        lean=commands.get('ubnfc-'+language,commands['classic-'+language]); rich=commands['classic-'+language]
        for case in json.loads((FIX/'cases.json').read_text()):
            actual=execute('native-'+language+'-'+case['id'],case['source'],lean,rich)
            prefix='OK\t1\tLEAN\tACCEPT\t' if oracle(case['source']).startswith('ACCEPT') else 'OK\t2\tRICH_FAILURE\tREJECT\t'
            if not actual.startswith(prefix):raise AssertionError(actual)
    counter=OUT/'calls.log'
    def fake(name,body,delay=0,mutation='',binding='normal',exit_code=0,oversized=False):
        path=OUT/(name+'.py')
        # All scripts below are newly written experiment fixtures, never engine copies.
        program='''#!/usr/bin/env python3
import pathlib,sys,time
p=pathlib.Path(sys.argv[1]);source=p.read_bytes()
with pathlib.Path(COUNTER).open('a') as log:log.write(NAME+'\\n')
time.sleep(DELAY)
if MUTATION:p.write_text(MUTATION)
if EXIT:raise SystemExit(EXIT)
grammar,snapshot=sys.argv[2:4]
if BINDING=='grammar':grammar='other-grammar'
if BINDING=='snapshot':snapshot='other-snapshot'
if BINDING=='source':source=b'other-source'
print('SNAPSHOT\\t'+grammar+'\\t'+snapshot+'\\t'+source.hex())
print('x'*70000 if OVERSIZED else BODY)
'''
        values=dict(COUNTER=str(counter),NAME=name,DELAY=delay,MUTATION=mutation,EXIT=exit_code,BINDING=binding,OVERSIZED=oversized,BODY=body)
        # json literals are Python-compatible for these values except booleans.
        program=program.replace('import pathlib,sys,time','import pathlib,sys,time\n'+ '\n'.join(k+'='+repr(v) for k,v in values.items()))
        path.write_text(program);path.chmod(0o700);return path
    accepted=oracle('p<a><b>[]!'); rejected='REJECT\t0\t0\t70\t70'
    good=fake('accept',accepted); bad=fake('reject',rejected)
    cases=[
        ('reject-then-accept','',bad,good,'PURE',5000,4096,'ERROR\t2\tLEAN_REJECT_RICH_ACCEPT'),
        ('ast-field-difference','p<a><b>[]!',fake('wrong-field',accepted.replace('s61','s7a')),good,'PURE',5000,4096,'ERROR\t1\tAST_OR_ACCEPTANCE_MISMATCH'),
        ('ast-span-difference','p<a><b>[]!',fake('wrong-span',accepted.replace('cp=1:4','cp=0:4')),good,'PURE',5000,4096,'ERROR\t1\tAST_OR_ACCEPTANCE_MISMATCH'),
        ('utf16-span-difference','p<a><b>[]!',fake('wrong-utf16',accepted.replace('utf16=1:4','utf16=1:5')),good,'PURE',5000,4096,'ERROR\t1\tAST_OR_ACCEPTANCE_MISMATCH'),
        ('source-text-difference','p<a><b>[]!',fake('wrong-text',accepted.replace('text=3c613e','text=3c623e')),good,'PURE',5000,4096,'ERROR\t1\tAST_OR_ACCEPTANCE_MISMATCH'),
        ('wrong-grammar','p<a><b>[]!',fake('wrong-grammar',accepted,binding='grammar'),good,'PURE',5000,4096,'ERROR\t1\tBINDING_MISMATCH'),
        ('wrong-snapshot','p<a><b>[]!',fake('wrong-snapshot',accepted,binding='snapshot'),good,'PURE',5000,4096,'ERROR\t1\tBINDING_MISMATCH'),
        ('wrong-source','p<a><b>[]!',fake('wrong-source',accepted,binding='source'),good,'PURE',5000,4096,'ERROR\t1\tBINDING_MISMATCH'),
        ('mutated-source','p<a><b>[]!',fake('mutated-source',accepted,mutation='changed'),good,'PURE',5000,4096,'ERROR\t1\tSNAPSHOT_MISMATCH'),
        ('grown-source','p<a><b>[]!',fake('grown-source',accepted,mutation='x'*4097),good,'PURE',5000,4096,'ERROR\t1\tINPUT_LIMIT'),
        ('deadline-before-start','p<a><b>[]!',good,good,'PURE',0,4096,'ERROR\t0\tDEADLINE'),
        ('deadline-in-engine','p<a><b>[]!',fake('slow',accepted,delay=1),good,'PURE',60,4096,'ERROR\t1\tDEADLINE'),
        ('shared-deadline','',fake('slow-reject',rejected,delay=.25),fake('slow-rich',rejected,delay=.25),'PURE',450,4096,'ERROR\t2\tDEADLINE'),
        ('input-limit','p<a><b>[]!',good,good,'PURE',5000,4,'ERROR\t0\tINPUT_LIMIT'),
        ('output-limit','p<a><b>[]!',fake('large-output',accepted,oversized=True),good,'PURE',5000,4096,'ERROR\t1\tOUTPUT_LIMIT'),
        ('engine-resource-exit','p<a><b>[]!',fake('resource-exit',accepted,exit_code=3),good,'PURE',5000,4096,'ERROR\t1\tENGINE_ERROR'),
        ('rich-process-failure','',bad,fake('rich-exit',rejected,exit_code=3),'PURE',5000,4096,'ERROR\t2\tENGINE_ERROR'),
        ('unsafe-provider','p<a><b>[]!',bad,good,'UNKNOWN',5000,4096,'OK\t1\tRICH_ONCE\t'+accepted),
        ('unsafe-callback','p<a><b>[]!',bad,good,'UNKNOWN',5000,4096,'OK\t1\tRICH_ONCE\t'+accepted),
    ]
    for name,text,lean,rich,purity,deadline,limit,expected in cases:
        counter.write_text('')
        actual=execute(name,text,lean,rich,purity,deadline,limit)
        if actual!=expected:raise AssertionError(f'{name}: {actual} != {expected}')
        calls=counter.read_text().splitlines()
        attempts=int(actual.split('\t')[1])
        if (not actual.endswith('\tDEADLINE') and len(calls)!=attempts) or len(calls)>attempts:
            raise AssertionError(f'wrong execution count: {name} {calls}')
        if purity=='UNKNOWN' and calls!=['accept']:raise AssertionError('callback/provider was replayed')
    source.write_text('p<a><b>[]!'); expected_file.write_bytes(b'x'*65537)
    actual=run([OUT/'driver',source,grammar,'snapshot-1','PURE',good,good,expected_file,5000,4096]).strip('\n')
    if actual!='ERROR\t0\tORACLE_LIMIT':raise AssertionError(actual)
    rows.append('oracle-limit\t'+actual)
    expected_file.write_text(accepted+'\n')
    for name,deadline,limit in [('invalid-deadline','no',4096),('negative-limit',5000,-1),('overflow-limit',5000,'9'*30)]:
        result=subprocess.run(list(map(str,[OUT/'driver',source,grammar,'snapshot-1','PURE',good,good,expected_file,deadline,limit])),capture_output=True,text=True)
        if result.returncode!=2 or 'panicked' in result.stderr:raise AssertionError(name+result.stderr)
        rows.append(name+'\tARGUMENT_ERROR\t2')
    (OUT/'driver.tsv').write_text('\n'.join(rows)+'\n')
    return len(rows)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--ubnfc',type=Path);parser.add_argument('--skip-build',action='store_true');args=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    private=args.ubnfc.resolve() if args.ubnfc else None
    if private:
        if run(['git','rev-parse','HEAD'],private).strip()!=UBNFC_PIN or run(['git','status','--porcelain'],private).strip():raise SystemExit('ubnfc must be clean at the fixed revision')
        # A fixture-only branch may differ from the pin; tested engine sources may not.
        changed=run(['git','diff','--name-only',CLASSIC_PIN,'--','unlaxer-common/src/main','unlaxer-dsl/src/main','rust/unlaxer-runtime','rust/unlaxer-codegen','rust/unlaxer-ubnf','rust/unlaxer-generator']).strip()
        if changed:raise SystemExit('four-route run requires pinned Classic engine sources: '+changed)
    if not args.skip_build:build(private)
    commands=compile_probes(private)
    counts=contract_checks();counts['inputs']=correctness(commands)
    grammar=hashlib.sha256((FIX/'Reuse.ubnf').read_bytes()).hexdigest()
    counts['driver']=driver_checks(commands,grammar)
    record=dict(classic=CLASSIC_PIN if private else run(['git','rev-parse','HEAD']).strip(), ubnfc=UBNFC_PIN if private else None,
                grammar_sha256=grammar, adapter_revision='engine-reuse-435-v1',
                adapter_sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(EX.iterdir()) if p.suffix in ['.java','.rs','.py']},
                routes=list(commands), counts=counts,
                requested_profile='typed-ast+source+cp+utf16; full-input; owned-snapshot; fixed-grammar; memo-preserved',
                bounds=dict(input_bytes=4096,reply_bytes=65536,engine_calls=2,deadline_ms=5000,java_heap='256 MiB'),
                performance=dict(generation='not measured',preparation='not measured',recognition='not measured',adapter='not measured',failure_diagnostics='not measured',heap='not measured',allocations='not measured'),
                conclusion='fixed-fixture reuse feasible; production adoption evidence insufficient; no new engine')
    (OUT/'manifest.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(record,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
