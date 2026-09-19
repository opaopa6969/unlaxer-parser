use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::sync::atomic::{AtomicUsize, Ordering};

static NEXT: AtomicUsize = AtomicUsize::new(0);
struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "unlaxer-mixed-{}-{}",
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
fn success(output: Output) {
    assert!(
        output.status.success(),
        "{}\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}
fn library(directory: &Path) -> PathBuf {
    let library = directory.join("libunlaxer_runtime.rlib");
    success(
        Command::new("rustc")
            .args([
                "--edition=2021",
                "--crate-type=rlib",
                "--crate-name=unlaxer_runtime",
            ])
            .arg(Path::new(env!("CARGO_MANIFEST_DIR")).join("../unlaxer-runtime/src/lib.rs"))
            .arg("-o")
            .arg(&library)
            .output()
            .unwrap(),
    );
    library
}
fn generate_and_run(directory: &Path, library: &Path, grammar: &str, probe: &str) {
    fs::create_dir(directory).unwrap();
    let grammar_file = directory.join("input.ubnf");
    fs::write(&grammar_file, grammar).unwrap();
    let output = directory.join("generated");
    for check in [false, true] {
        let mut command = Command::new(env!("CARGO_BIN_EXE_unlaxer"));
        command
            .args(["generate", "--grammar"])
            .arg(&grammar_file)
            .arg("--output")
            .arg(&output);
        if check {
            command.arg("--check");
        }
        success(
            command
                .env("PATH", "")
                .env("JAVA_HOME", "/nonexistent-unlaxer-java")
                .output()
                .unwrap(),
        );
    }
    fs::write(directory.join("main.rs"), probe).unwrap();
    success(
        Command::new("rustc")
            .arg("--edition=2021")
            .arg(directory.join("main.rs"))
            .arg("--extern")
            .arg(format!("unlaxer_runtime={}", library.display()))
            .arg("-o")
            .arg(directory.join("probe"))
            .output()
            .unwrap(),
    );
    success(Command::new(directory.join("probe")).output().unwrap());
}

#[test]
fn mixed_scalar_optional_list_and_helper_values_keep_order_and_owned_spans() {
    let directory = Directory::new();
    let library = library(&directory.0);
    for (index, (items, helper)) in [
        ("{ Factor @items }", ""),
        ("{ Factor } @items", ""),
        ("Items @items", "Items ::= { Factor };"),
        ("{ ('a' | '😀' | Leaf) @items }", ""),
    ]
    .into_iter()
    .enumerate()
    {
        let grammar = format!("grammar Mixed {{ @whitespace: javaStyle @root @mapping(Bundle,params=[head,maybe,items]) Document ::= Factor @head ':' [ Factor @maybe ] ':' {items}; Factor ::= 'a' | '😀' | Leaf; @mapping(Leaf,params=[value]) Leaf ::= 'x' @value; {helper} }}");
        generate_and_run(
            &directory.0.join(format!("mode-{index}")),
            &library,
            &grammar,
            r#"
mod generated;
use generated::{ast::{Ast, AstValue}, evaluator::{Semantics, evaluate}};
use unlaxer_runtime::Span;
struct Eval;
fn value(v: &AstValue, e: &mut Eval) -> String {
    match v { AstValue::Text { text, .. } => text.clone(), AstValue::Node(node) => evaluate(node, e).join("") }
}
impl Semantics for Eval {
    type Output = Vec<String>;
    fn eval_leaf(&mut self, value: &str, _: Span) -> Vec<String> { vec![value.into()] }
    fn eval_bundle(&mut self, head: &AstValue, maybe: Option<&AstValue>, items: &[AstValue], _: Span) -> Vec<String> {
        let mut result = vec![value(head, self)];
        if let Some(v) = maybe { result.push(value(v, self)); }
        result.extend(items.iter().map(|v| value(v, self))); result
    }
}
fn main() {
    for (source, expected) in [("a::ax😀x",vec!["a","a","x","😀","x"]), ("x:a:😀xaa",vec!["x","a","😀","x","a","a"]), ("a /*😀*/:x :ax😀",vec!["a","x","a","x","😀"]), ("a::",vec!["a"])] {
        let tree = generated::parser::parse_tree(source).unwrap();
        let ast = generated::mapper::map(&tree).unwrap(); drop(tree);
        assert_eq!(evaluate(&ast, &mut Eval),expected,"{source}");
        let Ast::Bundle { head,maybe,items,span } = &ast else { panic!("bundle") };
        assert_eq!(*span,Span{start:0,end:source.chars().count()});
        for v in std::iter::once(head).chain(maybe.iter()).chain(items.iter()) {
            let range=v.span(); assert!(range.start<=range.end && range.end<=span.end);
            if let AstValue::Text{text,..}=v {
                let slice:String=source.chars().skip(range.start).take(range.end-range.start).collect();
                assert_eq!(slice,*text); assert_eq!(range.end-range.start,1);
            }
        }
    }
    for source in ["", "a:", "a:z:", "a::axz"] { assert!(generated::parser::parse_tree(source).is_err(),"{source}"); }
}
"#,
        );
    }
}

#[test]
fn delimited_mixed_alias_preserves_the_capture_boundary_for_text_only() {
    let directory = Directory::new();
    let library = library(&directory.0);
    let grammar = "grammar G { @root @mapping(Bundle,params=[head,maybe,items]) Document ::= Outer @head ':' [ Outer @maybe ] ':' { Outer @items }; Outer ::= '(' Factor ')'; Factor ::= 'a' | '😀' | Leaf; @mapping(Leaf) Leaf ::= 'x'; }";
    generate_and_run(
        &directory.0.join("delimited"),
        &library,
        grammar,
        r#"
mod generated;
use generated::ast::{Ast, AstValue};
fn main() {
    for source in ["(a)::(😀)(x)(a)", "(x):(a):(😀)"] {
        let tree=generated::parser::parse_tree(source).unwrap();
        let node=generated::mapper::map(&tree).unwrap(); drop(tree);
        let Ast::Bundle{head,maybe,items,..}=node else {panic!("bundle")};
        let mut texts=Vec::new(); let mut nodes=0;
        for v in std::iter::once(&head).chain(maybe.iter()).chain(items.iter()) {
            match v {
                AstValue::Text{text,span}=>{
                    assert!(text.starts_with('(') && text.ends_with(')'));
                    assert_eq!(source.chars().skip(span.start).take(span.end-span.start).collect::<String>(),*text);
                    assert_eq!(span.end-span.start,3); texts.push(text.as_str());
                },
                AstValue::Node(node)=>{assert!(matches!(**node,Ast::Leaf{..})); assert_eq!(node.span().end-node.span().start,1); nodes+=1;},
            }
        }
        assert_eq!(nodes,1);
        assert_eq!(texts,if source.starts_with("(a)") {vec!["(a)","(😀)","(a)"]} else {vec!["(a)","(😀)"]});
    }
}
"#,
    );
}

#[test]
fn shared_mapping_promotes_text_and_node_in_both_orders() {
    let directory = Directory::new();
    let library = library(&directory.0);
    let text = "@mapping(Shared,params=[value]) Text ::= 'a' @value;";
    let node = "@mapping(Shared,params=[value]) Node ::= Leaf @value;";
    for (index, (first, second)) in [(text, node), (node, text)].into_iter().enumerate() {
        let grammar = format!("grammar G {{ @root Document ::= Text | Node; {first} {second} @mapping(Leaf,params=[text]) Leaf ::= 'x' @text; }}");
        generate_and_run(
            &directory.0.join(format!("shared-{index}")),
            &library,
            &grammar,
            r#"
mod generated;
use generated::ast::{Ast, AstValue};
fn main() {
    for source in ["a","x"] {
        let tree=generated::parser::parse_tree(source).unwrap();
        let node=generated::mapper::map(&tree).unwrap(); drop(tree);
        let Ast::Shared{value,..}=node else {panic!("shared")};
        match (source,value) {
            ("a",AstValue::Text{text,span}) => {assert_eq!(text,"a"); assert_eq!(span,unlaxer_runtime::Span{start:0,end:1});},
            ("x",AstValue::Node(node)) => assert!(matches!(*node,Ast::Leaf{..})),
            _=>panic!("wrong branch"),
        }
    }
}
"#,
        );
    }
}

#[test]
fn mixed_projection_rejects_incompatible_containers_and_semantic_separators() {
    for body in [
        "@root @mapping(Box,params=[value]) Document ::= Leaf % Factor @value; Factor ::= ['a' | Leaf]; @mapping(Leaf) Leaf ::= 'x';",
        "@root Document ::= A | B; @mapping(Shared,params=[value]) A ::= 'a' @value; @mapping(Shared,params=[value]) B ::= [Leaf] @value; @mapping(Leaf) Leaf ::= 'x';",
    ] {
        let error=unlaxer_generator::generate(&format!("grammar G {{ {body} }}")).unwrap_err();
        assert!(error.contains("mapped separator") || error.contains("incompatible shared mapping schema"),"{error}");
    }
}

#[test]
fn many_helpers_retain_each_scalar_item_boundary_without_joining_the_list() {
    let directory = Directory::new();
    let library = library(&directory.0);
    for (index, (body, cases)) in [
        ("{ Outer }", r#"("",vec![]),("(a)",vec!["(a)"]),("(a)(x)(😀)",vec!["(a)","node:x","(😀)"]),("(a)(a)",vec!["(a)","(a)"])"#),
        ("{ '(' Factor ')' }", r#"("",vec![]),("(a)",vec!["(a)"]),("(a)(x)(😀)",vec!["(a)","node:x","(😀)"]),("(a)(a)",vec!["(a)","(a)"])"#),
        ("Outer % ','", r#"("(a)",vec!["(a)"]),("(a),(x),(😀)",vec!["(a)","node:x","(😀)"]),("(a),(a)",vec!["(a)","(a)"])"#),
        ("('(' Factor ')') % ','", r#"("(a)",vec!["(a)"]),("(a),(x),(😀)",vec!["(a)","node:x","(😀)"]),("(a),(a)",vec!["(a)","(a)"])"#),
        ("Outer Outer", r#"("(a)(x)",vec!["(a)","node:x"]),("(a)(a)",vec!["(a)","(a)"]),("(x)(😀)",vec!["node:x","(😀)"])"#),
        ("[ Outer ] [ Outer ]", r#"("",vec![]),("(a)",vec!["(a)"]),("(a)(x)",vec!["(a)","node:x"]),("(a)(a)",vec!["(a)","(a)"])"#),
        ("[ '(' Factor ')' ] [ '<' Factor '>' ]", r#"("",vec![]),("(a)",vec!["(a)"]),("<a>",vec!["<a>"]),("(a)<x>",vec!["(a)","node:x"]),("(a)<a>",vec!["(a)","<a>"])"#),
    ].into_iter().enumerate() {
        let grammar=format!("grammar G {{ @root @mapping(Collection,params=[values]) Document ::= Items @values; Items ::= {body}; Outer ::= '(' Factor ')'; Factor ::= 'a' | '😀' | Leaf; @mapping(Leaf,params=[value]) Leaf ::= 'x' @value; }}");
        let probe=r#"
mod generated;
use generated::{ast::{Ast,AstValue},evaluator::{Semantics,evaluate}};
use unlaxer_runtime::Span;
struct Eval;
impl Semantics for Eval {
    type Output=Vec<String>;
    fn eval_leaf(&mut self,value:&str,_:Span)->Vec<String>{vec![format!("node:{value}")]}
    fn eval_collection(&mut self,values:&[AstValue],_:Span)->Vec<String>{values.iter().map(|value|match value{AstValue::Text{text,..}=>text.clone(),AstValue::Node(node)=>evaluate(node,self).join("")}).collect()}
}
fn main(){
    for (source,expected) in [// CASES
    ]{
        let tree=generated::parser::parse_tree(source).unwrap();
        let ast=generated::mapper::map(&tree).unwrap();drop(tree);
        assert_eq!(evaluate(&ast,&mut Eval),expected,"{source}");
        let Ast::Collection{values,..}=&ast else{panic!("collection")};
        for value in values{
            if let AstValue::Text{text,span}=value{
                let slice:String=source.chars().skip(span.start).take(span.end-span.start).collect();
                assert_eq!(&slice,text);assert_eq!(span.end-span.start,3);
            }
        }
    }
}
"#.replace("// CASES",cases);
        generate_and_run(&directory.0.join(format!("items-{index}")),&library,&grammar,&probe);
    }
}

#[test]
fn optional_helper_retains_absence_and_scalar_delimiters() {
    let directory = Directory::new();
    let library = library(&directory.0);
    generate_and_run(&directory.0.join("optional"),&library,
        "grammar G { @root @mapping(Box,params=[value]) Document ::= Outer @value; Outer ::= '(' [Factor] ')'; Factor ::= 'a' | Leaf; @mapping(Leaf) Leaf ::= 'x'; }",
        r#"
mod generated;
use generated::ast::{Ast,AstValue};
fn main(){for source in ["()","(a)","(x)"] {
    let tree=generated::parser::parse_tree(source).unwrap();
    let ast=generated::mapper::map(&tree).unwrap();drop(tree);
    let Ast::Box{value,..}=ast else{panic!("box")};
    match (source,value){
        ("()",None)=>{},
        ("(a)",Some(AstValue::Text{text,span}))=>{assert_eq!(text,"(a)");assert_eq!(span,unlaxer_runtime::Span{start:0,end:3});},
        ("(x)",Some(AstValue::Node(node)))=>assert!(matches!(*node,Ast::Leaf{..})),
        _=>panic!("incorrect optional helper projection"),
    }
}}
"#);
}

#[test]
fn pure_node_aliases_keep_typed_cardinality_and_owned_occurrence_spans() {
    let directory = Directory::new();
    let library = library(&directory.0);
    for (index, helper) in [
        "Alias ::= Leaf;",
        "Alias ::= Inner; Inner ::= Deep; Deep ::= Leaf;",
        "Alias ::= ((Leaf));",
        "Alias ::= '😀' '(' Leaf ')';",
        "Alias ::= Leaf | Other;",
        "Alias ::= Inner; Inner ::= Deep; Deep ::= Leaf | Other;",
        "Alias ::= ((Leaf | Other));",
        "Alias ::= '😀' '(' (Leaf | Other) ')';",
    ]
    .into_iter()
    .enumerate()
    {
        let grammar = format!("grammar G {{ @root @mapping(Bundle,params=[head,maybe,items]) Document ::= Alias @head ':' [ Alias @maybe ] ':' {{ Alias @items }}; {helper} @mapping(Leaf,params=[text]) Leaf ::= ('x' | 'y') @text; @mapping(Other,params=[text]) Other ::= ('z' | '🚀') @text; }}");
        let source = if index % 4 == 3 {
            if index < 4 {
                "😀(x):😀(y):😀(x)😀(x)"
            } else {
                "😀(x):😀(🚀):😀(z)😀(x)"
            }
        } else if index < 4 {
            "x:y:xx"
        } else {
            "x:🚀:zx"
        };
        let empty = if index % 4 == 3 { "😀(x)::" } else { "x::" };
        let probe = r#"
mod generated;
use generated::{ast::Ast,evaluator::{Semantics,evaluate}};
use unlaxer_runtime::Span;
struct Eval;
impl Semantics for Eval {
    type Output=Vec<(String,Span)>;
    fn eval_leaf(&mut self,text:&str,span:Span)->Self::Output{vec![(text.into(),span)]}
    fn eval_other(&mut self,text:&str,span:Span)->Self::Output{vec![(text.into(),span)]}
    fn eval_bundle(&mut self,head:&Ast,maybe:Option<&Ast>,items:&[Ast],_:Span)->Self::Output{
        std::iter::once(head).chain(maybe).chain(items.iter()).flat_map(|node|evaluate(node,self)).collect()
    }
}
fn main(){
    for source in ["SOURCE","EMPTY"] {
        let expected:Vec<_>=source.chars().enumerate().filter(|(_,c)|matches!(c,'x'|'y'|'z'|'🚀'))
            .map(|(start,c)|(c.to_string(),Span{start,end:start+1})).collect();
        let mut input=source.to_owned();
        let mut context=unlaxer_runtime::ParseContext::new(&input);
        let matched=generated::parser::parse_context(&mut context).unwrap();
        let tree=context.tree(matched.nodes[0]).unwrap();
        assert_eq!(context.position(),source.chars().count());
        assert_eq!(context.matched_position(),source.chars().count());
        let ast=generated::mapper::map(&tree).unwrap();
        drop(tree);drop(context);input.clear();drop(input);
        assert_eq!(evaluate(&ast,&mut Eval),expected);
        let Ast::Bundle{maybe,items,..}=&ast else{panic!("bundle")};
        assert_eq!(maybe.is_none(),source=="EMPTY");
        assert_eq!(items.len(),if source=="EMPTY"{0}else{2});
    }
}
"#.replace("SOURCE",source).replace("EMPTY",empty);
        generate_and_run(
            &directory.0.join(format!("pure-alias-{index}")),
            &library,
            &grammar,
            &probe,
        );
    }
}
