use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::sync::atomic::{AtomicUsize, Ordering};

static NEXT: AtomicUsize = AtomicUsize::new(0);
struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "unlaxer-native-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn repo() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("..")
}
fn success(output: Output) -> Output {
    assert!(
        output.status.success(),
        "{}\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    output
}
fn invoke(grammar: &Path, output: &Path, check: bool) -> Output {
    let mut command = Command::new(env!("CARGO_BIN_EXE_unlaxer"));
    command
        .args(["generate", "--grammar"])
        .arg(grammar)
        .arg("--output")
        .arg(output);
    if check {
        command.arg("--check");
    }
    // If the generator tries Java, Maven, Cargo or rustc during generation, this fails.
    command
        .env("PATH", "")
        .env("JAVA_HOME", "/nonexistent-unlaxer-java")
        .output()
        .unwrap()
}

#[test]
fn native_generation_needs_no_java_and_matches_the_committed_example() {
    let directory = Directory::new();
    let grammar = repo().join("../unlaxer-dsl/src/test/resources/evolution/3/Evolution.ubnf");
    let output = directory.0.join("generated");
    success(invoke(&grammar, &output, false));
    success(invoke(&grammar, &output, true));
    success(
        Command::new(env!("CARGO_BIN_EXE_unlaxer"))
            .args(["generate", "--target", "rust", "--grammar"])
            .arg(&grammar)
            .arg("--output")
            .arg(&output)
            .arg("--check")
            .env("PATH", "")
            .output()
            .unwrap(),
    );
    for name in ["mod.rs", "ast.rs", "parser.rs", "mapper.rs", "evaluator.rs"] {
        assert_eq!(
            fs::read(repo().join("examples/evolution/src/generated").join(name)).unwrap(),
            fs::read(output.join(name)).unwrap(),
            "{name}"
        );
    }
    fs::write(output.join("evaluator.rs"), "// handwritten\n").unwrap();
    assert_eq!(Some(4), invoke(&grammar, &output, true).status.code());
    assert_eq!(Some(4), invoke(&grammar, &output, false).status.code());
    assert_eq!(
        "// handwritten\n",
        fs::read_to_string(output.join("evaluator.rs")).unwrap()
    );
    let missing = directory.0.join("not-created");
    assert_eq!(Some(4), invoke(&grammar, &missing, true).status.code());
    assert!(!missing.exists());
}

#[test]
fn native_generated_operator_modules_compile_and_execute() {
    let directory = Directory::new();
    let grammar = repo().join("../unlaxer-dsl/src/test/resources/associative/Operators.ubnf");
    success(invoke(&grammar, &directory.0.join("generated"), false));
    let library = directory.0.join("libunlaxer_runtime.rlib");
    success(
        Command::new("rustc")
            .args([
                "--edition=2021",
                "--crate-type=rlib",
                "--crate-name=unlaxer_runtime",
            ])
            .arg(repo().join("unlaxer-runtime/src/lib.rs"))
            .arg("-o")
            .arg(&library)
            .output()
            .unwrap(),
    );
    let main = "mod generated; fn main() { let tree = generated::parser::parse_tree(\"10-3-2\").unwrap(); let ast = generated::mapper::map(&tree).unwrap(); drop(tree); if let generated::ast::Ast::Binary { op, right, .. } = ast { assert_eq!(op, vec![\"-\",\"-\"]); assert_eq!(right.len(), 2); } else { panic!(\"not binary\"); } }";
    fs::write(directory.0.join("main.rs"), main).unwrap();
    let compile = || {
        Command::new("rustc")
            .arg("--edition=2021")
            .arg(directory.0.join("main.rs"))
            .arg("--extern")
            .arg(format!("unlaxer_runtime={}", library.display()))
            .arg("-o")
            .arg(directory.0.join("probe"))
            .output()
            .unwrap()
    };
    success(compile());
    success(Command::new(directory.0.join("probe")).output().unwrap());
    fs::write(directory.0.join("main.rs"), "mod generated; struct Stale; impl generated::evaluator::Semantics for Stale { type Output = (); fn eval_number(&mut self, _: &str, _: unlaxer_runtime::Span) {} } fn main() {}").unwrap();
    let stale = compile();
    assert!(!stale.status.success());
    let errors = String::from_utf8_lossy(&stale.stderr);
    assert!(
        errors.contains("E0046") && errors.contains("eval_binary"),
        "{errors}"
    );
}

#[test]
fn lowerer_rejects_unsupported_or_inconsistent_grammars_without_artifacts() {
    let simple = "grammar G { @root @mapping(Value, params=[value]) Root ::= 'x' @value; }";
    let invalid = [
        (
            simple.replace("'x' @value", "Missing @value"),
            "unknown reference",
        ),
        (
            simple.replace("'x' @value", "[ 'x' ] Root @value"),
            "left recursion",
        ),
        (
            simple.replace("'x' @value", "{ [ 'x' ] } 'x' @value"),
            "nullable unbounded",
        ),
        (
            simple.replace("'x' @value", "[ [ 'x' ] ] @value"),
            "nested container capture",
        ),
        (simple.replace("'x' @value", "'' @value"), "empty literal"),
        (
            simple.replace("@root", "@root @rightAssoc"),
            "unsupported annotation",
        ),
        (
            simple.replace("@root", "@root @precedence(level=1)"),
            "occur together",
        ),
        (
            simple.replace("@root", "@root @leftAssoc @precedence(level=1)"),
            "requires left",
        ),
        (
            simple.replace("params=[value]", "params=[other]"),
            "params must match",
        ),
        (simple.replace("value", "span"), "reserved capture"),
        (
            simple.replace("grammar G {", "grammar G { token T = other.NumberParser\n"),
            "unsupported external token",
        ),
        (
            simple.replace("grammar G {", "grammar G { token T = REGEX('x')\n"),
            "unsupported token",
        ),
        (
            simple.replace("'x' @value", "ERROR('bad') @value"),
            "unsupported error",
        ),
        (
            simple.replace("grammar G {", "grammar G { @whitespace: python\n"),
            "unsupported setting",
        ),
        (format!("{simple}\n{simple}"), "exactly one grammar"),
    ];
    let directory = Directory::new();
    let grammar = directory.0.join("input.ubnf");
    let output = directory.0.join("generated");
    for (source, reason) in invalid {
        fs::write(&grammar, source).unwrap();
        let result = invoke(&grammar, &output, false);
        assert_eq!(
            Some(3),
            result.status.code(),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
        assert!(
            String::from_utf8_lossy(&result.stderr).contains(reason),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
        assert!(!output.exists());
    }
    assert!(unlaxer_generator::generate(
        &simple.replace("grammar G {", "grammar G { @whitespace: none\n")
    )
    .is_ok());
}

#[test]
fn cli_flags_and_preflight_do_not_overwrite_other_files() {
    let directory = Directory::new();
    let grammar = directory.0.join("input.ubnf");
    fs::write(
        &grammar,
        "grammar G { @root @mapping(Value) Root ::= 'x'; }",
    )
    .unwrap();
    let output = directory.0.join("generated");
    fs::create_dir(&output).unwrap();
    fs::write(
        output.join("parser.rs"),
        "// Generated by unlaxer RustBackend.\nprevious",
    )
    .unwrap();
    fs::write(output.join("evaluator.rs"), "handwritten").unwrap();
    assert_eq!(Some(4), invoke(&grammar, &output, false).status.code());
    assert_eq!(
        "// Generated by unlaxer RustBackend.\nprevious",
        fs::read_to_string(output.join("parser.rs")).unwrap()
    );
    assert!(!output.join("ast.rs").exists());
    for args in [
        vec![],
        vec!["generate"],
        vec!["generate", "--bogus"],
        vec!["generate", "--check", "--check"],
        vec!["generate", "--grammar", "x", "--grammar", "y"],
    ] {
        assert_eq!(
            Some(2),
            Command::new(env!("CARGO_BIN_EXE_unlaxer"))
                .args(args)
                .output()
                .unwrap()
                .status
                .code()
        );
    }
    success(
        Command::new(env!("CARGO_BIN_EXE_unlaxer"))
            .arg("--help")
            .output()
            .unwrap(),
    );
}

#[test]
fn structural_recursion_is_bounded_even_across_transparent_rules() {
    let mut source =
        String::from("grammar G { @root @mapping(Value,params=[value]) Root ::= R0 @value; ");
    for i in 0..300 {
        source.push_str(&format!("R{i} ::= '(' R{} ')'; ", i + 1));
    }
    source.push_str("R300 ::= 'x'; }");
    let error = unlaxer_generator::generate(&source).unwrap_err();
    assert!(error.contains("analysis depth"), "{error}");
}

#[test]
fn nullable_cardinality_shared_mapping_and_operator_boundaries_are_explicit() {
    for (accepted, body) in [
        (false, "token E=EMPTY @root @mapping(V,params=[values]) Root ::= ('a' | E)+ @values;"),
        (true, "token E=EMPTY @root @mapping(V,params=[values]) Root ::= E{1,2} @values;"),
        (false, "token E=EMPTY @root @mapping(V,params=[values]) Root ::= E % E @values;"),
        (false, "@root @mapping(V) Root ::= [ 'x' ] Root;"),
        (false, "@root @mapping(V) Root ::= Prefix Root | 'x'; Prefix ::= [ 'a' ];"),
        (false, "@root @mapping(V,params=[value]) Root ::= ('a' | Node) @value; @mapping(N) Node ::= 'n';"),
        (false, "@root @mapping(V,params=[value]) Root ::= ([ 'a' ]) @value;"),
        (false, "@root Root ::= A | B; @mapping(V,params=[value]) A ::= 'a' @value; @mapping(V,params=[value]) B ::= ['b'] @value;"),
        (false, "@root Root ::= A | B; @mapping(FooBar) A ::= 'a'; @mapping(Foo_bar) B ::= 'b';"),
        (false, "@root @mapping(V,params=[left,op,right]) @leftAssoc @precedence(level=10) Root ::= Base @left { '+' @op Base @right }; @mapping(W,params=[left,op,right]) @leftAssoc @precedence(level=10) Base ::= Atom @left {'*' @op Atom @right}; Atom ::= 'a';"),
        (false, "@root @mapping(V,params=[value]) @precedence(level=10) Root ::= 'x' @value;"),
        (false, "@root @mapping(V,params=[left,op,right]) @leftAssoc Root ::= Atom @left {'+' @op Atom @right}; Atom ::= 'a';"),
        (true, "@root @mapping(V,params=[value]) Root ::= 'a' @value | 'b';"),
        (true, "@root @mapping(V,params=[values]) Root ::= 'a' @values 'b' @values;"),
    ] {
        let result = unlaxer_generator::generate(&format!("grammar G {{ {body} }}"));
        assert_eq!(accepted, result.is_ok(), "{body}: {result:?}");
    }
}

#[cfg(unix)]
#[test]
fn cli_refuses_output_file_directory_and_ancestor_symlinks() {
    use std::os::unix::fs::symlink;
    let directory = Directory::new();
    let grammar = directory.0.join("input.ubnf");
    fs::write(
        &grammar,
        "grammar G { @root @mapping(Value) Root ::= 'x'; }",
    )
    .unwrap();
    let target = directory.0.join("target");
    fs::create_dir(&target).unwrap();
    let link = directory.0.join("link");
    symlink(&target, &link).unwrap();
    assert_eq!(Some(4), invoke(&grammar, &link, false).status.code());
    assert_eq!(
        Some(4),
        invoke(
            &grammar,
            &PathBuf::from(format!("{}/", link.display())),
            false
        )
        .status
        .code()
    );
    assert_eq!(
        Some(4),
        invoke(&grammar, &link.join("."), false).status.code()
    );
    assert_eq!(
        Some(4),
        invoke(&grammar, &link.join("../unexpected"), false)
            .status
            .code()
    );
    assert_eq!(
        Some(4),
        invoke(&grammar, &link.join("nested"), false).status.code()
    );
    let external = directory.0.join("external.rs");
    fs::write(&external, "original").unwrap();
    symlink(&external, target.join("ast.rs")).unwrap();
    assert_eq!(Some(4), invoke(&grammar, &target, false).status.code());
    assert_eq!("original", fs::read_to_string(external).unwrap());
    assert!(fs::symlink_metadata(target.join("ast.rs"))
        .unwrap()
        .file_type()
        .is_symlink());
}
