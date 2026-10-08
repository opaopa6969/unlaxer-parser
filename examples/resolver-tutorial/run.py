#!/usr/bin/env python3
"""Build and run the actual generated Java/Rust examples; no parser is implemented here."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
TARGET = HERE / "target"


def command(args, **kwargs):
    print("+ " + " ".join("<classpath>" if i > 0 and str(args[i - 1]) == "-cp" else str(a) for i, a in enumerate(args)), file=sys.stderr)
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def build():
    TARGET.mkdir(exist_ok=True)
    command(["mvn", "-q", "-pl", "unlaxer-common,unlaxer-dsl", "-am", "package", "dependency:build-classpath",
             "-DskipTests", "-Dmaven.javadoc.skip=true", "-Dmdep.outputFile=target/resolver-tutorial-classpath.txt",
             "-DincludeScope=test"], cwd=REPO)
    dependencies = (REPO / "unlaxer-dsl/target/resolver-tutorial-classpath.txt").read_text().strip()
    cp = os.pathsep.join([str(REPO / "unlaxer-dsl/target/classes"), str(REPO / "unlaxer-common/target/classes"), dependencies])
    classes = TARGET / "classes"
    classes.mkdir(exist_ok=True)
    generated = TARGET / "generated-java"
    command(["javac", "--release", "17", "-cp", cp, "-d", classes, HERE / "java/Generate.java"])
    command(["java", "-cp", os.pathsep.join([str(classes), cp]), "Generate", HERE / "address.ubnf", generated])
    command(["javac", "--release", "17", "-cp", cp, "-d", classes,
             *sorted(generated.glob("*.java")), *sorted(p for p in (HERE / "java").glob("*.java") if p.name != "Generate.java")])
    (TARGET / "classpath.txt").write_text(os.pathsep.join([str(classes), cp]))
    command(["cargo", "run", "--quiet", "--locked", "--manifest-path", REPO / "rust/Cargo.toml", "-p", "unlaxer-generator", "--",
             "generate", "--grammar", HERE / "address.ubnf", "--output", HERE / "rust/src/generated"])
    command(["cargo", "build", "--quiet", "--locked", "--manifest-path", HERE / "rust/Cargo.toml"])


def executable(host):
    if host == "java":
        return ["java", "-cp", (TARGET / "classpath.txt").read_text(), "example.resolvers.Main"]
    return [str(HERE / "rust/target/debug/resolver-tutorial")]


def check():
    fixtures = json.loads((HERE / "cases.json").read_text())
    report = ["case\thost\texit\texpected\tactual"]
    with tempfile.TemporaryDirectory(prefix="resolver tutorial 𠮷 ") as tmp:
        root = Path(tmp)
        shutil.copytree(HERE / "config", root / "config")
        shutil.copytree(HERE / "data", root / "data")
        caller = root / "unrelated-cwd"
        caller.mkdir()
        for case in fixtures:
            config = root / case.get("config", "config/case.json")
            if "document" in case:
                config.write_text(json.dumps(case["document"], ensure_ascii=False), encoding="utf-8")
            if "raw" in case:
                config.write_text(case["raw"], encoding="utf-8")
            if "data" in case:
                (root / "data/case.json").write_text(json.dumps(case["data"], ensure_ascii=False), encoding="utf-8")
            for host in ("java", "rust"):
                result = subprocess.run([*executable(host), str(config), case["input"]], cwd=caller,
                                        capture_output=True, text=True, encoding="utf-8", timeout=15)
                assert result.returncode == case["exit"], (case["name"], host, result.returncode, result.stdout, result.stderr)
                actual = json.loads(result.stdout)
                assert actual == case["expected"], (case["name"], host, case["expected"], actual)
                report.append("\t".join([case["name"], host, str(result.returncode),
                                         json.dumps(case["expected"], ensure_ascii=False), json.dumps(actual, ensure_ascii=False)]))
    command(["java", "-cp", (TARGET / "classpath.txt").read_text(), "example.resolvers.SnapshotCheck"])
    command(["cargo", "test", "--quiet", "--locked", "--manifest-path", HERE / "rust/Cargo.toml"])
    (TARGET / "conformance.tsv").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(f"{len(fixtures)} cases × Java / Rust: OK")
    print(TARGET / "conformance.tsv")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["build", "check", "java", "rust"])
    parser.add_argument("configuration", nargs="?")
    parser.add_argument("input", nargs="?")
    args = parser.parse_args()
    if args.action == "build":
        build()
    elif args.action == "check":
        check()
    else:
        if args.configuration is None or args.input is None:
            parser.error("java / rust require CONFIG.json INPUT")
        raise SystemExit(subprocess.run([*executable(args.action), args.configuration, args.input]).returncode)


if __name__ == "__main__":
    main()
