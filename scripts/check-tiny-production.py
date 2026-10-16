#!/usr/bin/env python3
"""Run actual pinned Tiny parsers, both host bridges, and independent source/query oracles."""
import argparse, os, pathlib, subprocess, difflib, re, json, signal
from contextlib import contextmanager
import xml.etree.ElementTree as ET
PIN='f86ce8a5ab0ab7d23fdba2cd477187df8aa7607c'
root=pathlib.Path(__file__).resolve().parents[1]
env=dict(os.environ,CARGO_INCREMENTAL='0')
mvn=['mvn','-B']
COMPATIBILITY=[]
def run(command,cwd=root,timeout=300):
    print('+', ' '.join(map(str,command)),flush=True)
    completed=subprocess.run(list(map(str,command)),cwd=cwd,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
    if completed.returncode:print(completed.stdout);raise SystemExit(completed.returncode)
    return completed.stdout
@contextmanager
def current_dependencies(version):
    # Capture bytes before either Maven invocation; even a partially failed rewrite is restored.
    originals = {module/'pom.xml': (module/'pom.xml').read_bytes() for _,module,_,_ in COMPATIBILITY}
    previous_handler = signal.getsignal(signal.SIGTERM)
    def terminate(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, terminate)
    try:
        for label,module,_,_ in COMPATIBILITY:
            # use-dep-version alone leaves the root's ${unlaxer.version} property unchanged.
            options = ['set-property','-Dproperty=unlaxer.version','-DnewVersion='+version] if label=='tiny' else [
                'use-dep-version','-Dincludes=org.unlaxer:unlaxer-common,org.unlaxer:unlaxer-dsl',
                '-DdepVersion='+version,'-DforceVersion=true']
            run(mvn+['org.codehaus.mojo:versions-maven-plugin:2.18.0:'+options[0],
                *options[1:],'-DgenerateBackupPoms=false'],module)
        yield
    finally:
        for path,original in originals.items():
            if not path.exists() or path.read_bytes()!=original: path.write_bytes(original)
        signal.signal(signal.SIGTERM, previous_handler)

def report_paths(directory, name):
    return list(directory.glob('TEST-*.'+name+'.xml')) + list(directory.glob('TEST-'+name+'.xml'))

def verify_compatibility(label, module, tests, expected_count):
    directory=module/'target/surefire-reports'
    names=tests.split(',')
    for name in names:
        for file in report_paths(directory,name): file.unlink()
    command=['-Dgpg.skip=true','-Dtinyexpression.skipRailroad=true','-Dtest='+tests,
        'install' if label=='tiny' else 'test']
    run(mvn+command,module,600)
    reports=[]
    for name in names:
        paths=report_paths(directory,name)
        if len(paths)!=1: raise SystemExit('missing or duplicate current compatibility report: '+name)
        reports.append(ET.parse(paths[0]).getroot())
    counts={key:sum(int(report.get(key,'0')) for report in reports) for key in ['tests','failures','errors','skipped']}
    if counts!={'tests':expected_count,'failures':0,'errors':0,'skipped':0}:
        raise SystemExit('invalid '+label+' current compatibility results: '+str(counts))
    (out/(label+'-compatibility.json')).write_text(json.dumps(counts,indent=2)+'\n')
    print(label+' current-version compatibility: '+str(counts),flush=True)

def main():
    global mvn, COMPATIBILITY, out
    p=argparse.ArgumentParser();p.add_argument('tiny',type=pathlib.Path);p.add_argument('--skip-build',action='store_true');p.add_argument('--maven-repo');args=p.parse_args()
    tiny=args.tiny.resolve();out=root/'target/tiny-production';out.mkdir(parents=True,exist_ok=True)
    mvn=['mvn','-B']+(['-Dmaven.repo.local='+args.maven_repo] if args.maven_repo else [])
    COMPATIBILITY = [
        ('tiny', tiny, 'FormulaInfoSourceDocumentTest,FormulaInfoParserTest,P4EngineModeMatrixTest,P4ParserEngineTest,P4SourceMappingTest,P4OwnedSliceSourceTest,P4JavaCodeEmitterSourceTextTest', 125),
        ('ide', tiny/'tools/tinyexpression-p4-lsp-vscode', 'TinyExpressionP4LanguageServerExtTest,TinyExpressionP4DebugAdapterExtTest', 39),
    ]

    if subprocess.check_output(['git','rev-parse','HEAD'],cwd=tiny,text=True).strip()!=PIN:raise SystemExit('wrong Tiny source pin')
    original=tiny/'src/test/resources/formulaInfo-test/69/formulaInfo.txt';fixture=root/'docs/fixtures/tinyexpression-production'
    if original.read_bytes()!=(fixture/'original.txt').read_bytes():raise SystemExit('production fixture copy differs from pin')
    if subprocess.check_output(['git','status','--porcelain'],cwd=tiny,text=True).strip():
        raise SystemExit('Tiny checkout must be clean; use a dedicated clone, never a dirty user checkout')
    version_output=run(mvn+['-q','-Dstyle.color=never','help:evaluate','-Dexpression=revision','-DforceStdout'])
    versions=re.findall(r'(?m)^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9._-]+)?$',re.sub(r'\x1b\[[0-9;]*m','',version_output).strip())
    if len(versions)!=1:raise SystemExit('expected exactly one current Maven revision: '+version_output)
    version=versions[0]
    print('Current unlaxer revision under test: '+version,flush=True)
    if not args.skip_build:
        run(mvn+['-pl','.,unlaxer-common,unlaxer-dsl','-DskipTests','-Dgpg.skip=true','install'],timeout=600)
    with current_dependencies(version):
        for label,module,tests,count in COMPATIBILITY:
            dependency_file=out/(label+'-dependencies.txt')
            dependency_file.unlink(missing_ok=True)
            run(mvn+['org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree','-Dincludes=org.unlaxer:*','-DoutputFile='+str(dependency_file)],module)
            resolved=dict(re.findall(r'org\.unlaxer:(unlaxer-(?:common|dsl)):jar:([^:\s]+):',dependency_file.read_text()))
            if resolved!={'unlaxer-common':version,'unlaxer-dsl':version}:
                raise SystemExit(label+' did not resolve the current unlaxer dependencies: '+str(resolved))
            print(label+' resolved current unlaxer dependencies: '+str(resolved),flush=True)
            if not args.skip_build:
                verify_compatibility(label,module,tests,count)
        (out/'current-dependencies.json').write_text(json.dumps({'revision':version,'tiny_pin':PIN,
            'compatibility_tests':0 if args.skip_build else 164,'skip_build':args.skip_build},indent=2)+'\n')
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
        print('PASS: current unlaxer '+version+('; bridge-only, compatibility tests skipped' if args.skip_build else '; 164 current-version compatibility tests')+'; pinned production parsers, both hosts, independent regions / diagnostics / queries / edits / snapshot guards; '+str(len(expected.splitlines()))+' observations per host')

if __name__=='__main__':
    main()
