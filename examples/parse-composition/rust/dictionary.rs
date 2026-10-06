use unlaxer_runtime::{ParseContext, ParseMatch, ParseResult, Span};

/// Ordered dictionary snapshot, selected by the host's region binding.
pub fn word_token(context: &mut ParseContext<'_>) -> ParseResult {
    let region = context
        .binding_values("region")
        .first()
        .map(String::as_str)
        .unwrap_or("default");
    let length = context
        .binding_values(&format!("words.{region}"))
        .iter()
        .find(|word| !word.is_empty() && context.remaining().starts_with(word.as_str()))
        .map(|word| word.chars().count());
    match length {
        Some(length) => {
            let start = context.position();
            assert!(context.advance(length));
            Ok(ParseMatch::empty(Span {
                start,
                end: context.position(),
            }))
        }
        None => Err(context.error("dictionary word")),
    }
}
