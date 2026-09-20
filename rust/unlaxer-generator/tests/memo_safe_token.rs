use unlaxer_generator::lowering;
use unlaxer_ubnf::parse;

fn lower(settings_and_tokens: &str) -> Result<unlaxer_codegen::ir::GrammarIr, String> {
    let source = format!(
        "grammar G {{ {settings_and_tokens} \
         @root @mapping(Root,params=[value]) Root ::= 'x' @value; }}"
    );
    let file = parse(&source).map_err(|error| error.to_string())?;
    lowering::lower(&file.grammars[0])
}

#[test]
fn accepts_multiple_distinct_simple_aliases_as_a_noop() {
    lower(
        "@memoSafeToken: NUMBER @memoSafeToken: IDENTIFIER \
         token NUMBER = org.unlaxer.parser.elementary.NumberParser \
         token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser",
    )
    .unwrap();
}

#[test]
fn validates_memo_safe_aliases_deterministically() {
    for (input, expected) in [
        (
            "@memoSafeToken: NUMBER @memoSafeToken: NUMBER \
             token NUMBER = org.unlaxer.parser.elementary.NumberParser",
            "duplicate memoSafeToken alias NUMBER",
        ),
        (
            "@memoSafeToken: MISSING \
             token NUMBER = org.unlaxer.parser.elementary.NumberParser",
            "memoSafeToken undefined token alias MISSING",
        ),
        (
            "@memoSafeToken: BUILTIN token BUILTIN = REGEX('[a-z]+')",
            "memoSafeToken alias must name a Simple token BUILTIN",
        ),
        (
            "@memoSafeToken: { alias: 'NUMBER' } \
             token NUMBER = org.unlaxer.parser.elementary.NumberParser",
            "memoSafeToken requires token alias string",
        ),
    ] {
        let error = lower(input).unwrap_err();
        assert!(
            error.contains(expected),
            "{error:?} does not contain {expected:?}"
        );
    }
}
