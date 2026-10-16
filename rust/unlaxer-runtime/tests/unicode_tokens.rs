use unlaxer_runtime::{source::Snapshot, Expr, ParseContext};

/// Java REGEX is not available in Rust. These are the shared literal/ANY
/// contracts for raw Unicode text and scalar consumption, from the same corpus.
#[test]
fn shared_literal_and_any_preserve_unicode_scalar_tokens() {
    for row in include_str!("../../../spec-corpus/unicode-tokens/cases.tsv")
        .lines()
        .filter(|row| !row.starts_with('#'))
    {
        let fields: Vec<&str> = row.split('\t').collect();
        let text = fields[1];
        let length: usize = fields[2].parse().unwrap();
        let source = Snapshot::new("fixture", 1, text).unwrap();
        assert_eq!(source.len(), length, "{}", fields[0]);
        assert_eq!(source.utf16(length).unwrap(), fields[3].parse().unwrap());
        assert_eq!(source.utf8(length).unwrap(), fields[4].parse().unwrap());
        let input = format!("{text}!");
        let mut literal = ParseContext::new(&input);
        let token = literal.parse(&Expr::Literal(text)).unwrap();
        assert_eq!(literal.text(token.span), Some(text), "{}", fields[0]);
        assert_eq!(literal.position(), length, "{}", fields[0]);
        literal.parse(&Expr::Literal("!")).unwrap();
        assert_eq!(literal.remaining(), "");

        let mut any = ParseContext::new(text);
        let mut rebuilt = String::new();
        for point in 0..length {
            let token = any.parse(&Expr::Any).unwrap();
            let scalar = any.text(token.span).unwrap();
            if point == 0 {
                assert_eq!(scalar, fields[5], "{}", fields[0]);
            }
            rebuilt.push_str(scalar);
            assert_eq!(any.position(), point + 1, "{}", fields[0]);
        }
        assert_eq!(rebuilt, text, "{}", fields[0]);
        assert!(any.parse(&Expr::Any).is_err());
        assert_eq!(any.position(), length);
    }
}
