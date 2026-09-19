mod support;
use std::{fs, path::PathBuf, process::Command};
use unlaxer_codegen::*;

struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        static COUNTER: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let path = std::env::temp_dir().join(format!(
            "unlaxer-native-codegen-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

#[test]
fn evolution_matches_all_five_java_generated_files() {
    let root =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../examples/evolution/src/generated");
    for file in generate(&support::fixture("evolution")).unwrap() {
        assert_eq!(
            fs::read_to_string(root.join(&file.relative_path)).unwrap(),
            file.content,
            "{}",
            file.relative_path
        );
    }
}

#[test]
fn generated_modules_compile_evaluate_and_require_semantics() {
    let temp = Temp::new();
    let runtime = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../unlaxer-runtime/src/lib.rs");
    let runtime_lib = temp.0.join("libunlaxer_runtime.rlib");
    let result = Command::new("rustc")
        .args([
            "--edition=2021",
            "--crate-name",
            "unlaxer_runtime",
            "--crate-type",
            "rlib",
        ])
        .arg(runtime)
        .arg("-o")
        .arg(&runtime_lib)
        .output()
        .unwrap();
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    for fixture in ["evolution", "fields", "shared", "names"] {
        let output = temp.0.join(fixture);
        fs::create_dir(&output).unwrap();
        let mut ir = support::fixture(if fixture == "names" {
            "evolution"
        } else {
            fixture
        });
        if fixture == "names" {
            ir.rules[0].name = "_Root".into();
            ir.rules[3].name = "self".into();
        }
        for file in generate(&ir).unwrap() {
            fs::write(output.join(file.relative_path), file.content).unwrap();
        }
        let probe = if matches!(fixture, "evolution" | "names") {
            r#"
use generated::{ast::Ast,evaluator::{Semantics,evaluate}};
use unlaxer_runtime::Span;
struct Eval;
impl Semantics for Eval {
 type Output=i32;
 fn eval_number(&mut self,value:&str,_:Span)->i32 { value.parse().unwrap() }
 fn eval_binary(&mut self,left:&Ast,op:&str,right:&Ast,_:Span)->i32 {let a=evaluate(left,self); let b=evaluate(right,self); if op=="+" {a+b} else {a*b}}
 fn eval_negation(&mut self,value:&Ast,_:Span)->i32 {-evaluate(value,self)}
 fn eval_conditional(&mut self,condition:&Ast,then_expr:&Ast,else_expr:&Ast,_:Span)->i32 {if evaluate(condition,self)!=0 {evaluate(then_expr,self)} else {evaluate(else_expr,self)}}
}
fn main() { let tree=generated::parser::parse_tree("if(1, 3*3, neg(2))").unwrap(); let ast=generated::mapper::map(&tree).unwrap(); drop(tree); assert_eq!(evaluate(&ast,&mut Eval),9); assert_eq!(ast.span().end,18); assert!(ast.canonical_json().contains("Conditional")); }
"#
        } else if fixture == "fields" {
            r#"fn main() {let tree=generated::parser::parse_tree("name 'hi' \"x\" 1 2 3").unwrap(); let ast=generated::mapper::map(&tree).unwrap(); drop(tree); let json=ast.canonical_json(); assert!(json.contains("hi")); assert!(json.contains("children"));}"#
        } else {
            r#"fn main() {for source in ["a","b"] {let tree=generated::parser::parse_tree(source).unwrap(); let ast=generated::mapper::map(&tree).unwrap(); assert!(ast.canonical_json().contains(source));} assert_eq!(generated::parser::OPERATORS[0].rule,"Right");}"#
        };
        let header = format!("#[path={:?}] mod generated;\n", output.join("mod.rs"));
        let source = temp.0.join(format!("{fixture}.rs"));
        fs::write(&source, format!("{header}{probe}")).unwrap();
        let binary = temp.0.join(format!("{fixture}-probe"));
        let result = Command::new("rustc")
            .args(["--edition=2021", "--extern"])
            .arg(format!("unlaxer_runtime={}", runtime_lib.display()))
            .arg(&source)
            .arg("-o")
            .arg(&binary)
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "{fixture}: {}",
            String::from_utf8_lossy(&result.stderr)
        );
        let result = Command::new(&binary).output().unwrap();
        assert!(
            result.status.success(),
            "{fixture}: {}",
            String::from_utf8_lossy(&result.stderr)
        );
        fs::write(&source,format!("{header}struct Missing; impl generated::evaluator::Semantics for Missing {{type Output=();}} fn main(){{}}")).unwrap();
        let result = Command::new("rustc")
            .args(["--edition=2021", "--extern"])
            .arg(format!("unlaxer_runtime={}", runtime_lib.display()))
            .arg(&source)
            .arg("-o")
            .arg(&binary)
            .output()
            .unwrap();
        assert!(!result.status.success());
        assert!(String::from_utf8_lossy(&result.stderr).contains("E0046"));
    }
}

#[test]
fn invalid_ir_is_rejected_before_emission() {
    let base = support::fixture("evolution");
    let mut cases = Vec::new();
    let mut g = base.clone();
    g.root = 999;
    cases.push(g);
    let mut g = base.clone();
    g.rules[0].body = Expression::Reference(999);
    cases.push(g);
    let mut g = base.clone();
    g.rules[0].name = g.rules[1].name.clone();
    cases.push(g);
    let mut g = base.clone();
    g.rules[0].name.clear();
    cases.push(g);
    let mut g = base.clone();
    g.rules[1].mapping.as_mut().unwrap().name = "Self".into();
    cases.push(g);
    let mut g = base.clone();
    g.rules[1].mapping.as_mut().unwrap().fields[0].name = "span".into();
    cases.push(g);
    let mut g = base.clone();
    g.rules[1].mapping.as_mut().unwrap().fields[0].name = "missing".into();
    cases.push(g);
    let mut g = base.clone();
    g.rules[1].mapping.as_mut().unwrap().name = "Number".into();
    cases.push(g);
    let mut g = base.clone();
    g.rules[1].mapping.as_mut().unwrap().name = "number".into();
    cases.push(g);
    let mut g = base.clone();
    g.rules[3].body = Expression::QuotedToken('x');
    cases.push(g);
    let mut g = base.clone();
    g.rules[3].body = Expression::Literal(String::new());
    cases.push(g);
    let mut g = base.clone();
    g.rules[3].body = Expression::CharRangeToken { min: 'z', max: 'a' };
    cases.push(g);
    let mut g = base.clone();
    g.rules[3].body = Expression::Repeat {
        child: Box::new(Expression::NumberToken),
        min: 2,
        max: Some(1),
    };
    cases.push(g);
    for (index, case) in cases.iter().enumerate() {
        assert!(generate(case).is_err(), "case {index}");
    }
}

#[test]
fn expression_variants_escape_unicode_and_control_characters() {
    use Expression::*;
    let mut grammar = support::fixture("shared");
    grammar.rules.push(Rule {
        name: "Tokens".into(),
        mapping: None,
        operator: None,
        body: Sequence(vec![
            Literal("\"\\\n\r\t\0\u{7f}😀".into()),
            AnyToken,
            EofToken,
            EmptyToken,
            CharRangeToken { min: 'a', max: 'z' },
            ExceptToken("!".into()),
            ExceptToken(String::new()),
            UntilToken("#".into()),
            UntilToken(String::new()),
            LookaheadToken {
                pattern: "x".into(),
                positive: false,
            },
            Separated {
                child: Box::new(NumberToken),
                separator: Box::new(Literal(",".into())),
            },
            Repeat {
                child: Box::new(IdentifierToken),
                min: 1,
                max: Some(2),
            },
        ]),
    });
    let files = generate(&grammar).unwrap();
    let parser = &files[2].content;
    for expected in [
        "\\\"\\\\\\n\\r\\t\\u{0}\\u{7f}😀",
        "Expr::Any",
        "Expr::Eof",
        "Expr::JavaEmpty",
        "Expr::CharRange('\\u{61}', '\\u{7a}')",
        "Expr::Except(\"!\")",
        "Expr::JavaUntil(\"#\")",
        "positive: false",
        ".separated_by(",
        ".repeat_java(1, Some(2))",
    ] {
        assert!(parser.contains(expected), "{expected}");
    }
}
