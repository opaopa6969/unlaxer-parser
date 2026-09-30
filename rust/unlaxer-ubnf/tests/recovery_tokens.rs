use unlaxer_ubnf::{parse, AnnotationKind, RecoveryMode};

#[test]
fn sync_token_lists_use_java_trim_not_unicode_whitespace() {
    for (list, expected) in [
        (" ; , } , ", vec![";", "}"]),
        ("", vec![]),
        (",,", vec![]),
        ("\u{00a0}", vec!["\u{00a0}"]),
        ("\u{2003}", vec!["\u{2003}"]),
        ("\u{0085},\u{202f}", vec!["\u{0085}", "\u{202f}"]),
    ] {
        let source = format!("grammar G {{ @recovery(sync='{list}') R ::= 'x'; }}");
        let file = parse(&source).unwrap();
        assert_eq!(
            file.grammars[0].rules[0].annotations[0].kind,
            AnnotationKind::Recovery {
                mode: RecoveryMode::Sync,
                sync_tokens: expected.into_iter().map(str::to_owned).collect(),
            }
        );
    }
}
