mod generated;

fn main() {
    let input = std::env::args().nth(1).expect("input");
    let mut context = unlaxer_runtime::ParseContext::new(&input);
    let accepted = generated::parser::parse_context(&mut context).is_ok();
    let diagnostic = generated::parser::parse_tree_detailed(&input)
        .err()
        .map_or_else(|| "null".to_owned(), |error| error.canonical_json());
    println!(
        "{{\"prefix\":[{},{},{}],\"diagnostic\":{}}}",
        accepted, context.position(), context.matched_position(), diagnostic
    );
}
