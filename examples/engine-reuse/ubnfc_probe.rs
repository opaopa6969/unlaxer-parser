mod canonical;
#[path = "../../target/engine-reuse/ubnfc-rust/mod.rs"]
mod generated;
use generated::ast::Ast;
fn adapt(n: &Ast, source: &str) -> String {
    let p = n.span().expect("typed node span");
    let span = canonical::span(source, p[0], p[1]);
    match n {
        Ast::g_ReuseAST_2e_Packet(p) => format!(
            "Packet({span},first={},second={},others=[{}])",
            adapt(&p.g_first, source),
            adapt(&p.g_second, source),
            p.g_others
                .iter()
                .map(|n| adapt(n, source))
                .collect::<Vec<_>>()
                .join(",")
        ),
        Ast::g_ReuseAST_2e_Atom(p) => {
            format!("Atom({span},value={})", canonical::values(&p.g_value))
        }
        _ => panic!("unexpected AST variant"),
    }
}
fn main() {
    let source = std::fs::read_to_string(std::env::args().nth(1).unwrap()).unwrap();
    if let Some(revision) = std::env::args().nth(2) {
        println!(
            "SNAPSHOT\t{}\t{}\t{}",
            revision,
            std::env::args().nth(3).unwrap(),
            canonical::hex(&source)
        );
    }
    let result = generated::parse_with_options(
        &source,
        generated::api::ParseOptions {
            diagnostics: true,
            ..Default::default()
        },
    );
    if result.ok {
        let ast = result.ast.as_ref().unwrap();
        let before = adapt(ast, &source);
        assert!(generated::parse("p<a><b>[]!").ok);
        assert_eq!(before, adapt(ast, &source));
        println!("ACCEPT\t{before}");
    } else {
        let d = &result.diagnostics[0];
        println!(
            "REJECT\t{}\t{}\t{}\t{}",
            d.offset_cp,
            d.farthest_cp,
            canonical::hex(&d.expected.join("|")),
            canonical::hex(&d.farthest_expected.join("|"))
        );
    }
}
