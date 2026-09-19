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
fn generated_mapper_dispatches_to_out_of_line_rule_mappers() {
    let grammar = support::fixture("evolution");
    let mapper = generate(&grammar)
        .unwrap()
        .into_iter()
        .find(|file| file.relative_path == "mapper.rs")
        .unwrap()
        .content;
    let dispatch = mapper
        .split("fn map_node(tree: &Tree, id: usize) -> Result<Vec<Ast>, String> {")
        .nth(1)
        .unwrap()
        .split("\n#[inline(never)]")
        .next()
        .unwrap();
    assert!(!dispatch.contains("Ast::r#"));
    for (index, _rule) in grammar
        .rules
        .iter()
        .enumerate()
        .filter(|(_, rule)| rule.mapping.is_some())
    {
        assert!(dispatch.contains(&format!("{index} => map_rule_{index}(tree, id),")));
        assert!(mapper.contains(&format!(
            "#[inline(never)]\nfn map_rule_{index}(tree: &Tree, id: usize) -> Result<Vec<Ast>, String>"
        )));
    }
}

#[test]
fn wide_generated_mapper_runs_on_two_mib_thread_stack_in_debug_build() {
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

    let generated = temp.0.join("generated");
    fs::create_dir(&generated).unwrap();
    for file in generate(&support::wide_deep_mapper_fixture(140, 24)).unwrap() {
        fs::write(generated.join(file.relative_path), file.content).unwrap();
    }
    let source = temp.0.join("small_stack.rs");
    let input = format!("{}z", "x".repeat(23));
    fs::write(
        &source,
        format!(
            "#[path={:?}] mod generated;\nfn main() {{ std::thread::Builder::new().stack_size(2 * 1024 * 1024).spawn(|| {{ let tree = generated::parser::parse_tree({input:?}).unwrap(); let ast = generated::mapper::map(&tree).unwrap(); assert_eq!(ast.span().start, 0); }}).unwrap().join().unwrap(); }}",
            generated.join("mod.rs")
        ),
    )
    .unwrap();
    let binary = temp.0.join("small-stack-probe");
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
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    let result = Command::new(binary).output().unwrap();
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
}

#[test]
fn generated_parser_reuses_one_grammar_and_keeps_the_rules_snapshot_api() {
    let parser = generate(&support::fixture("evolution"))
        .unwrap()
        .into_iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap()
        .content;
    assert!(parser.contains("static GRAMMAR: OnceLock<SharedGrammar>"));
    assert!(parser.contains("pub fn grammar() -> &'static SharedGrammar"));
    assert!(parser.contains("pub fn rules() -> Vec<Rule>"));
    assert!(parser.contains("context.parse_shared_grammar(grammar()"));
    assert!(parser.contains("pub fn parse_tree_with_options(source: &str, options: ParseOptions)"));
    assert!(parser
        .contains("pub fn parse_tree_detailed_with_options(source: &str, options: ParseOptions)"));
    assert!(parser.contains("parse_detailed_shared_with_options(grammar()"));
    assert!(!parser.contains("parse_detailed(&rules()"));
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
    for fixture in [
        "evolution",
        "fields",
        "shared",
        "names",
        "right",
        "mixed",
        "boundaries",
        "scope_effects",
    ] {
        let output = temp.0.join(fixture);
        fs::create_dir(&output).unwrap();
        let mut ir = support::fixture(if matches!(fixture, "names" | "scope_effects") {
            "evolution"
        } else if fixture == "right" {
            "shared"
        } else {
            fixture
        });
        if fixture == "names" {
            ir.rules[0].name = "_Root".into();
            ir.rules[3].name = "self".into();
        }
        if fixture == "right" {
            ir.rules[1].operator.as_mut().unwrap().associativity = Associativity::Left;
            ir.rules[2].operator.as_mut().unwrap().associativity = Associativity::Right;
        }
        if fixture == "scope_effects" {
            ir.rules[2].body = Expression::RuleEffects {
                child: Box::new(ir.rules[2].body.clone()),
                effects: RuleEffects {
                    scope_mode: Some(ScopeMode::Dynamic),
                    declares: Some(Declaration {
                        symbol_capture: "value".into(),
                        description: Some("documentation".into()),
                    }),
                    backref: Some("value".into()),
                },
            };
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
fn main() { let _:Vec<unlaxer_runtime::Rule>=generated::parser::rules(); let options=unlaxer_runtime::ParseOptions::with_memoization(unlaxer_runtime::Memoization::SafeFailures); let tree=generated::parser::parse_tree_with_options("if(1, 3*3, neg(2))",options).unwrap(); let ast=generated::mapper::map(&tree).unwrap(); drop(tree); assert_eq!(evaluate(&ast,&mut Eval),9); assert_eq!(ast.span().end,18); assert!(ast.canonical_json().contains("Conditional")); }
"#
        } else if fixture == "scope_effects" {
            r#"fn main() { let tree=generated::parser::parse_tree("2+3").unwrap(); assert_eq!(tree.scopes().all_declarations().len(),2); assert_eq!(tree.scopes().all_references().len(),2); assert!(tree.scopes().diagnostics().is_empty()); assert!(tree.scopes().is_declared("2")); let ast=generated::mapper::map(&tree).unwrap(); drop(tree); assert!(ast.canonical_json().contains("Binary")); }"#
        } else if fixture == "fields" {
            r#"fn main() {let tree=generated::parser::parse_tree("name 'hi' \"x\" 1 2 3").unwrap(); let ast=generated::mapper::map(&tree).unwrap(); drop(tree); let json=ast.canonical_json(); assert!(json.contains("hi")); assert!(json.contains("children"));}"#
        } else if fixture == "mixed" {
            include_str!("support/mixed_probe.rs.txt")
        } else if fixture == "boundaries" {
            include_str!("support/boundaries_probe.rs.txt")
        } else if fixture == "right" {
            r#"fn main() {use generated::parser::{Associativity,OPERATORS}; for source in ["a","b"] {let tree=generated::parser::parse_tree(source).unwrap(); let ast=generated::mapper::map(&tree).unwrap(); assert!(ast.canonical_json().contains(source));} assert_eq!(OPERATORS[0].rule,"Right"); assert_eq!(OPERATORS[0].associativity,Associativity::Right); assert_eq!(OPERATORS[1].associativity,Associativity::Left);}"#
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
fn right_metadata_does_not_change_existing_non_right_output() {
    let mut ir = support::fixture("shared");
    ir.rules[1].operator.as_mut().unwrap().associativity = Associativity::Left;
    let original = generate(&ir).unwrap();
    assert!(original[2]
        .content
        .contains("pub enum Associativity { Left, None }"));
    assert!(!original[2].content.contains("Associativity::Right"));
    ir.rules[2].operator.as_mut().unwrap().associativity = Associativity::Right;
    let right = generate(&ir).unwrap();
    for index in [0, 1, 3, 4] {
        assert_eq!(original[index], right[index]);
    }
    assert_eq!(
        original[2].content,
        right[2]
            .content
            .replace("{ Left, Right, None }", "{ Left, None }")
            .replace("Associativity::Right", "Associativity::None")
    );
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
    g.rules[3].body = Expression::TextValue(Box::new(Expression::Reference(999)));
    cases.push(g);
    let mut g = base.clone();
    g.rules[3].body = Expression::ValueBoundary(Box::new(Expression::Reference(999)));
    cases.push(g);
    let mut g = base.clone();
    g.rules[3].body = Expression::RuleEffects {
        child: Box::new(Expression::Reference(999)),
        effects: RuleEffects::default(),
    };
    cases.push(g);
    let mut g = base.clone();
    g.rules[3].body = Expression::RuleEffects {
        child: Box::new(Expression::NumberToken),
        effects: RuleEffects {
            backref: Some("missing".into()),
            ..RuleEffects::default()
        },
    };
    cases.push(g);
    let mut g = base.clone();
    g.rules[3].body = Expression::TriviaScope {
        child: Box::new(Expression::Reference(999)),
        java_whitespace: true,
    };
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
        catalog: None,
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
            CodeStartToken,
            CodeEndToken,
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
        "Expr::CodeStart",
        "Expr::CodeEnd",
    ] {
        assert!(parser.contains(expected), "{expected}");
    }
}
