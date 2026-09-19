#[rustfmt::skip]
pub mod generated;
pub mod semantics;

#[cfg(test)]
mod shared_grammar_tests {
    use super::generated::{mapper, parser};
    use std::sync::Arc;
    use unlaxer_runtime::ParseContext;

    #[test]
    fn generated_grammar_is_initialized_once_across_concurrent_parses() {
        let parses = (0..12)
            .map(|index| {
                std::thread::spawn(move || {
                    let grammar = parser::grammar();
                    let address = Arc::as_ptr(grammar) as *const () as usize;
                    let source = if index % 2 == 0 { "2+3" } else { "neg(4)" };
                    let tree = parser::parse_tree(source).unwrap();
                    let ast = mapper::map(&tree).unwrap();
                    (address, ast.canonical_json())
                })
            })
            .collect::<Vec<_>>();

        let expected_address = Arc::as_ptr(parser::grammar()) as *const () as usize;
        for parse in parses {
            let (address, ast) = parse.join().unwrap();
            assert_eq!(address, expected_address);
            assert!(ast.contains("span"));
        }
        assert_eq!(parser::grammar_initialization_count(), 1);
    }

    #[test]
    fn generated_shared_grammar_does_not_share_parse_context_state() {
        let mut first = ParseContext::new("2+3");
        let mut second = ParseContext::new("4*5");
        first.set_state("request", "first".to_owned());
        second.set_state("request", "second".to_owned());

        let first_match = parser::parse_context(&mut first).unwrap();
        let second_match = parser::parse_context(&mut second).unwrap();

        assert_eq!(
            first.state::<String>("request").map(String::as_str),
            Some("first")
        );
        assert_eq!(
            second.state::<String>("request").map(String::as_str),
            Some("second")
        );
        assert_eq!(first_match.span.end, 3);
        assert_eq!(second_match.span.end, 3);
        assert_eq!(parser::grammar_initialization_count(), 1);
    }
}
