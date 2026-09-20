//! Span-free structural interchange used only by the conformance probe.
use unlaxer_ubnf::*;

pub fn quote(text: &str) -> String {
    let mut out = String::from("\"");
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c < ' ' => {
                use std::fmt::Write;
                write!(out, "\\u{:04x}", c as u32).unwrap();
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}
fn list(values: impl IntoIterator<Item = String>) -> String {
    format!("[{}]", values.into_iter().collect::<Vec<_>>().join(","))
}
fn tag(name: &str, values: impl IntoIterator<Item = String>) -> String {
    list(std::iter::once(quote(name)).chain(values))
}
fn strings(values: &[String]) -> String {
    list(values.iter().map(|s| quote(s)))
}
fn optional(value: &Option<String>) -> String {
    value
        .as_ref()
        .map_or_else(|| "null".to_owned(), |s| quote(s))
}

pub fn file(file: &UbnfFile) -> String {
    tag(
        "UBNFFile",
        [list(file.grammars.iter().map(|g| {
            tag(
                "GrammarDecl",
                [
                    quote(&g.name),
                    list(
                        g.imports
                            .iter()
                            .map(|i| tag("ImportDecl", [quote(&i.alias), quote(&i.path)])),
                    ),
                    list(g.settings.iter().map(|s| {
                        tag(
                            "GlobalSetting",
                            [
                                quote(&s.key),
                                match &s.value {
                                    SettingValue::String(s) => {
                                        tag("StringSettingValue", [quote(s)])
                                    }
                                    SettingValue::Block(entries) => tag(
                                        "BlockSettingValue",
                                        [list(entries.iter().map(|e| {
                                            tag("KeyValuePair", [quote(&e.key), quote(&e.value)])
                                        }))],
                                    ),
                                },
                            ],
                        )
                    })),
                    list(g.tokens.iter().map(token)),
                    list(g.rules.iter().map(|r| {
                        tag(
                            "RuleDecl",
                            [
                                list(r.annotations.iter().map(annotation)),
                                quote(&r.name),
                                body(&r.body),
                            ],
                        )
                    })),
                ],
            )
        }))],
    )
}
fn token(t: &TokenDecl) -> String {
    let (name, args) = match &t.kind {
        TokenKind::Simple { parser_class } => ("Simple", vec![quote(parser_class)]),
        TokenKind::Until { terminator } => ("Until", vec![quote(terminator)]),
        TokenKind::Negation { excluded_chars } => ("Negation", vec![quote(excluded_chars)]),
        TokenKind::Lookahead { pattern } => ("Lookahead", vec![quote(pattern)]),
        TokenKind::NegativeLookahead { pattern } => ("NegativeLookahead", vec![quote(pattern)]),
        TokenKind::Any => ("Any", vec![]),
        TokenKind::Eof => ("Eof", vec![]),
        TokenKind::Empty => ("Empty", vec![]),
        TokenKind::CharRange { min, max } => (
            "CharRange",
            vec![quote(&min.to_string()), quote(&max.to_string())],
        ),
        TokenKind::CaseInsensitive { word } => ("CaseInsensitive", vec![quote(word)]),
        TokenKind::Regex { pattern } => ("Regex", vec![quote(pattern)]),
    };
    tag(name, std::iter::once(quote(&t.name)).chain(args))
}
fn annotation(a: &Annotation) -> String {
    let (name, args) = match &a.kind {
        AnnotationKind::Root => ("RootAnnotation", vec![]),
        AnnotationKind::Mapping { class_name, params } => (
            "MappingAnnotation",
            vec![quote(class_name), strings(params)],
        ),
        AnnotationKind::Eval {
            kind,
            strategy,
            params,
        } => {
            let mut params = params.iter().collect::<Vec<_>>();
            params.sort_by_key(|p| &p.key);
            let params = format!(
                "{{{}}}",
                params
                    .iter()
                    .map(|p| format!("{}:{}", quote(&p.key), quote(&p.value)))
                    .collect::<Vec<_>>()
                    .join(",")
            );
            ("EvalAnnotation", vec![quote(kind), quote(strategy), params])
        }
        AnnotationKind::Whitespace { style } => ("WhitespaceAnnotation", vec![optional(style)]),
        AnnotationKind::Interleave { profile } => ("InterleaveAnnotation", vec![quote(profile)]),
        AnnotationKind::Backref { name } => ("BackrefAnnotation", vec![quote(name)]),
        AnnotationKind::ScopeTree { mode } => ("ScopeTreeAnnotation", vec![quote(mode)]),
        AnnotationKind::Declares {
            symbol_capture,
            description,
        } => (
            "DeclaresAnnotation",
            vec![quote(symbol_capture), optional(description)],
        ),
        AnnotationKind::Catalog { context } => ("CatalogAnnotation", vec![quote(context)]),
        AnnotationKind::LeftAssoc => ("LeftAssocAnnotation", vec![]),
        AnnotationKind::RightAssoc => ("RightAssocAnnotation", vec![]),
        AnnotationKind::LongestChoice => ("LongestChoiceAnnotation", vec![]),
        AnnotationKind::PredictiveChoice => ("PredictiveChoiceAnnotation", vec![]),
        AnnotationKind::Precedence { level } => ("PrecedenceAnnotation", vec![level.to_string()]),
        AnnotationKind::Doc { text } => ("DocAnnotation", vec![quote(text)]),
        AnnotationKind::Recovery { mode, sync_tokens } => (
            "RecoveryAnnotation",
            vec![
                quote(match mode {
                    RecoveryMode::Sync => "SYNC",
                    RecoveryMode::Auto => "AUTO",
                    RecoveryMode::Skip => "SKIP",
                }),
                strings(sync_tokens),
            ],
        ),
        AnnotationKind::Skip => ("SkipAnnotation", vec![]),
        AnnotationKind::Simple { name } => ("SimpleAnnotation", vec![quote(name)]),
        AnnotationKind::CommonField { fields } => ("CommonFieldAnnotation", vec![strings(fields)]),
        AnnotationKind::Enum => ("EnumAnnotation", vec![]),
    };
    tag(name, args)
}
fn body(b: &RuleBody) -> String {
    tag(
        "body",
        [list(
            b.alternatives
                .iter()
                .map(|s| list(s.elements.iter().map(element))),
        )],
    )
}
fn element(e: &AnnotatedElement) -> String {
    tag(
        "AnnotatedElement",
        [
            atomic(&e.element),
            optional(&e.capture),
            e.typeof_constraint
                .as_ref()
                .map_or_else(|| "null".to_owned(), |s| tag("TypeofElement", [quote(s)])),
        ],
    )
}
fn atomic(e: &AtomicElement) -> String {
    match &e.kind {
        ElementKind::Group(b) => tag("GroupElement", [body(b)]),
        ElementKind::Optional(b) => tag("OptionalElement", [body(b)]),
        ElementKind::Repeat(b) => tag("RepeatElement", [body(b)]),
        ElementKind::OneOrMore(e) => tag("OneOrMoreElement", [atomic(e)]),
        ElementKind::BoundedRepeat { element, min, max } => tag(
            "BoundedRepeatElement",
            [
                atomic(element),
                min.to_string(),
                max.unwrap_or(i32::MAX as u32).to_string(),
            ],
        ),
        ElementKind::Separated { element, separator } => {
            tag("SeparatedElement", [atomic(element), atomic(separator)])
        }
        ElementKind::Terminal(s) => tag("TerminalElement", [quote(s)]),
        ElementKind::RuleRef { namespace, name } => {
            tag("RuleRefElement", [optional(namespace), quote(name)])
        }
        ElementKind::Error(s) => tag("ErrorElement", [quote(s)]),
    }
}
