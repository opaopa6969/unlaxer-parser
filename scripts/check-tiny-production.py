#!/usr/bin/env python3
"""Run actual pinned Tiny parsers, both host bridges, and independent source/query oracles."""
import argparse, os, pathlib, subprocess, difflib, xml.etree.ElementTree as ET
PIN='f86ce8a5ab0ab7d23fdba2cd477187df8aa7607c'
p=argparse.ArgumentParser();p.add_argument('tiny',type=pathlib.Path);p.add_argument('--skip-build',action='store_true');p.add_argument('--maven-repo');args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];tiny=args.tiny.resolve();out=root/'target/tiny-production';out.mkdir(parents=True,exist_ok=True)
env=dict(os.environ,CARGO_INCREMENTAL='0');mvn=['mvn','-B']+(['-Dmaven.repo.local='+args.maven_repo] if args.maven_repo else [])
def run(command,cwd=root,timeout=300):
    print('+', ' '.join(map(str,command)),flush=True)
    completed=subprocess.run(list(map(str,command)),cwd=cwd,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
    if completed.returncode:print(completed.stdout);raise SystemExit(completed.returncode)
    return completed.stdout
if subprocess.check_output(['git','rev-parse','HEAD'],cwd=tiny,text=True).strip()!=PIN:raise SystemExit('wrong Tiny source pin')
original=tiny/'src/test/resources/formulaInfo-test/69/formulaInfo.txt';fixture=root/'docs/fixtures/tinyexpression-production'
if original.read_bytes()!=(fixture/'original.txt').read_bytes():raise SystemExit('production fixture copy differs from pin')
if not args.skip_build:
    run(mvn+['-pl','unlaxer-common,unlaxer-dsl','-am','-DskipTests','package'])
    tests='FormulaInfoSourceDocumentTest,FormulaInfoParserTest,P4EngineModeMatrixTest,P4ParserEngineTest,P4SourceMappingTest,P4OwnedSliceSourceTest,P4JavaCodeEmitterSourceTextTest'
    run(mvn+['-Dgpg.skip=true','-Dtinyexpression.skipRailroad=true','-Dtest='+tests,'install'],tiny,600)
    run(mvn+['-Dtest=TinyExpressionP4LanguageServerExtTest,TinyExpressionP4DebugAdapterExtTest','test'],tiny/'tools/tinyexpression-p4-lsp-vscode',600)
    for directory in [tiny/'target/surefire-reports',tiny/'tools/tinyexpression-p4-lsp-vscode/target/surefire-reports']:
        reports=[ET.parse(file).getroot() for file in directory.glob('TEST-*.xml')]
        if not reports or any(int(r.get(k,'0')) for r in reports for k in ['failures','errors','skipped']):raise SystemExit('missing, failing or skipped compatibility tests')
run(mvn+['dependency:build-classpath','-Dmdep.includeScope=test','-Dmdep.outputFile='+str(out/'tiny-classpath')],tiny)
cp=os.pathsep.join(map(str,[root/'unlaxer-common/target/classes',root/'unlaxer-dsl/target/classes',tiny/'target/classes']))+os.pathsep+(out/'tiny-classpath').read_text().strip()
run(['javac','-cp',cp,'-d',out,*sorted((root/'examples/tinyexpression-production').glob('*.java'))])
run(['rustup','run','1.85.0','rustc','--edition=2021','-C','opt-level=1','--crate-name=unlaxer_runtime','--crate-type=rlib',root/'rust/unlaxer-runtime/src/lib.rs','-o',out/'libunlaxer_runtime.rlib'])
env['CARGO_PKG_VERSION']='2.0.0' # Rust package version at the same source pin; Java POM is 2.0.1.
run(['rustup','run','1.85.0','rustc','--edition=2021','-C','opt-level=1','--crate-name=tinyexpression_rs','--crate-type=rlib',tiny/'rust/tinyexpression-rs/src/lib.rs','-o',out/'libtinyexpression_rs.rlib'])
run(['rustup','run','1.85.0','rustc','--edition=2021','--extern','unlaxer_runtime='+str(out/'libunlaxer_runtime.rlib'),'--extern','tinyexpression_rs='+str(out/'libtinyexpression_rs.rlib'),root/'examples/tinyexpression-production/probe.rs','-o',out/'probe'])
expected=(fixture/'expected.tsv').read_text()
for language,command in [('java',['java','-cp',str(out)+os.pathsep+cp,'Probe']),('rust',[out/'probe'])]:
    actual=run(command+[fixture,pathlib.Path(os.environ.get('JAVA_HOME','/usr')).joinpath('bin/java'),cp],timeout=180)
    (out/(language+'.tsv')).write_text(actual)
    if actual!=expected:
        print(''.join(difflib.unified_diff(expected.splitlines(True),actual.splitlines(True),fromfile='independent expected',tofile=language)));raise SystemExit(1)
run(['rustup','run','1.85.0','rustc','--edition=2021','--extern','unlaxer_runtime='+str(out/'libunlaxer_runtime.rlib'),'--extern','tinyexpression_rs='+str(out/'libtinyexpression_rs.rlib'),root/'examples/tinyexpression-production/partial_probe.rs','-o',out/'partial-probe'])
partial_expected=(fixture/'partial/expected.tsv').read_text()
for language,command in [('java',['java','-cp',str(out)+os.pathsep+cp,'PartialProbe']),('rust',[out/'partial-probe'])]:
    actual=run(command+[fixture,pathlib.Path(os.environ.get('JAVA_HOME','/usr')).joinpath('bin/java'),cp],timeout=180)
    (out/('partial-'+language+'.tsv')).write_text(actual)
    if actual!=partial_expected:
        print(''.join(difflib.unified_diff(partial_expected.splitlines(True),actual.splitlines(True),fromfile='independent partial expected',tofile=language)));raise SystemExit(1)
compat=run(['java','-cp',str(out)+os.pathsep+cp,'Compatibility',fixture/'original.txt'])
if compat!='UBNFC\t10\tPASS\nCLASSIC\t10\tPASS\n':raise SystemExit('public P4 facade mismatch: '+compat)
(out/'compatibility.tsv').write_text(compat)
print('PASS: pinned production parsers, both hosts, independent regions / diagnostics / queries / edits / snapshot guards; '+str(len(expected.splitlines()))+' observations per host')
