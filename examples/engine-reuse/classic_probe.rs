mod canonical;
#[path = "../../target/engine-reuse/classic-rust/mod.rs"]
mod generated;
use generated::ast::Ast;
fn adapt(n: &Ast, source: &str) -> String {
    let p = n.span();
    let span = canonical::span(source, p.start, p.end);
    match n {
        Ast::Packet {
            first,
            second,
            others,
            ..
        } => format!(
            "Packet({span},first={},second={},others=[{}])",
            adapt(first, source),
            adapt(second, source),
            others
                .iter()
                .map(|n| adapt(n, source))
                .collect::<Vec<_>>()
                .join(",")
        ),
        Ast::Atom { value, .. } => format!("Atom({span},value={})", canonical::values(value)),
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
    match generated::parser::parse_tree_detailed_with_options(
        &source,
        unlaxer_runtime::ParseOptions::default()
            .with_diagnostics(unlaxer_runtime::Diagnostics::Detailed),
    ) {
        Ok(tree) => {
            let ast = generated::mapper::map(&tree).unwrap();
            let before = adapt(&ast, &source);
            generated::parser::parse_tree("p<a><b>[]!").unwrap();
            assert_eq!(before, adapt(&ast, &source));
            println!("ACCEPT\t{before}");
        }
        Err(d) => println!(
            "REJECT\t{}\t{}\t{}\t{}",
            d.offset,
            d.farthest.offset,
            canonical::hex(&d.expected.join("|")),
            canonical::hex(&d.farthest.expected.join("|"))
        ),
    }
}
