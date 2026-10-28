//! Authoring diagnostics, in Java GrammarValidator order (reference d8af6d9b).
//! This is separate from lowering's fail-fast generation contract. It reuses the
//! existing pure analyses; no parser execution or host class loading takes place.
//! The exact supported branches and remaining gaps are in docs/ubnf-authoring-lsp.md.
mod recursion;
mod rules;
use crate::adapters::AdapterRegistry;
use unlaxer_ubnf::*;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Issue {
    pub code: String,
    pub message: String,
    pub hint: String,
    pub rule: Option<String>,
}
impl Issue {
    fn new(
        code: &str,
        message: impl Into<String>,
        hint: impl Into<String>,
        rule: Option<&str>,
    ) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            hint: hint.into(),
            rule: rule.map(str::to_owned),
        }
    }
}
fn add(out: &mut Vec<Issue>, code: &str, message: impl Into<String>, hint: impl Into<String>) {
    out.push(Issue::new(code, message, hint, None));
}
fn failure(out: &mut Vec<Issue>, result: Result<(), String>, code: &str, hint: &str) {
    if let Err(message) = result {
        add(out, code, message, hint);
    }
}

/// Collect the authoring subset without imposing lowering's root/schema prerequisites.
/// Do not interpret an empty result as proof that every Java validation passed.
pub fn collect(grammar: &GrammarDecl) -> Vec<Issue> {
    let mut out = vec![];
    for token in &grammar.tokens {
        if matches!(token.kind, TokenKind::Regex { .. }) {
            out.push(Issue::new("E-REGEX-RETIRED", "E-REGEX-RETIRED: REGEX execution was removed; use token NAME ::= expression; (see docs/regex-retirement.md)", "Use token NAME ::= expression;", Some(&token.name)));
        }
    }
    let cycles = recursion::cycles(grammar);
    match &cycles {
        Ok(cycles) if !cycles.is_empty() => add(&mut out, "E-LEFT-RECURSION", format!("E-LEFT-RECURSION: left recursion at {}; rewrite using repetition or input-consuming right recursion", cycles[0]), "Rewrite using repetition or input-consuming right recursion."),
        Err(message) => add(&mut out, "E-LEFT-RECURSION-LIMIT", message, "Rewrite using repetition or input-consuming right recursion."),
        _ => {}
    }
    for token in &grammar.tokens {
        if format_two(grammar)
            && matches!(token.kind, TokenKind::CharRange { min, max } if min as u32 <= 0xdfff && max as u32 >= 0xd800)
        {
            add(
                &mut out,
                "E-TOKEN-RANGE-SURROGATE",
                "range must not contain surrogates",
                "Split the scalar range around the surrogate block.",
            );
        }
    }
    // Java completes scalar-range validation before the separate CI-token pass.
    for token in &grammar.tokens {
        if matches!(&token.kind, TokenKind::CaseInsensitive { word } if word.is_empty()) {
            add(
                &mut out,
                "E-CI-EMPTY",
                format!("CI token {} must not be empty", token.name),
                "Use at least one Unicode code point in CI(...).",
            );
        }
    }
    failure(
        &mut out,
        crate::lexical::compile_syntax(grammar)
            .map(|_| ())
            .map_err(|message| format!("E-LEXICAL: {message}")),
        "E-LEXICAL",
        "Correct the declarative token expression.",
    );
    for (issues, hint) in [
        (
            AdapterRegistry::from_grammar(grammar).1,
            "Correct the token adapter declaration or registration.",
        ),
        (
            crate::adapters::token_contract_diagnostics(grammar),
            "Correct the format-2 token contract declaration.",
        ),
        (
            feature_diagnostics(grammar),
            "Use a supported format-2 @feature declaration.",
        ),
    ] {
        for issue in issues {
            add(
                &mut out,
                issue.code,
                format!("{}: {}", issue.code, issue.subject),
                hint,
            );
        }
    }
    for issue in crate::token_stream::problems(grammar) {
        add(
            &mut out,
            issue.code,
            issue.subject,
            "Use the supported @tokenStream:enabled profile.",
        );
    }
    for issue in crate::global_comment::problems(grammar) {
        add(
            &mut out,
            issue.code,
            issue.subject,
            if issue.code == "E-COMMENT-SETTING" {
                "Use a single @comment: { line: '//' } setting."
            } else {
                "Put line comments into the named whitespace token, or use @whitespace: javaStyle."
            },
        );
    }
    // W-COMMENT-IGNORED is emitted once by GenerationWarnings with its source span.
    if let Err(message) = crate::lexical_contexts::validate(grammar) {
        // LexicalContexts propagates LexicalCompiler's Java-prefixed message.
        let message = if message.starts_with("E-") {
            message
        } else {
            format!("E-LEXICAL: {message}")
        };
        let code = if message.starts_with("E-LEXICAL-CONTEXT-") {
            message.split(':').next().unwrap()
        } else {
            "E-LEXICAL-CONTEXT"
        };
        add(
            &mut out,
            code,
            &message,
            "Select non-nullable declarative tokens and nonempty unique literals.",
        );
    }
    failure(
        &mut out,
        crate::embedded::validate(grammar),
        "E-EMBEDDING",
        "Use one scalar body capture and exact language identity.",
    );
    for issue in crate::raising::problems(grammar) {
        out.push(Issue::new(
            issue.code,
            format!("{}: {}: {}", issue.code, issue.rule, issue.subject),
            "Name only labels raised by LABEL(...) and give CATCH at least one label.",
            Some(&issue.rule),
        ));
    }
    globals(grammar, &mut out);
    tokens(grammar, &mut out);
    rules::collect(grammar, &mut out);
    let advisory: Vec<_> = match cycles {
        Ok(cycles) => cycles,
        Err(message) => vec![format!("analysis limit: {message}")],
    }
    .into_iter()
    .map(|cycle| {
        let limit = cycle.starts_with("analysis limit:");
        let first = cycle.split(" ->").next().unwrap();
        Issue::new(
            if limit {
                "W-LEFT-RECURSION-LIMIT"
            } else {
                "W-LEFT-RECURSION"
            },
            if limit {
                cycle.clone()
            } else {
                format!("left-recursive cycle detected: {cycle}")
            },
            "Rewrite using repetition (e.g. A ::= B { Op B }) or right recursion.",
            Some(first),
        )
    })
    .collect();
    if grammar
        .rules
        .iter()
        .any(|r| has(r, |a| matches!(a, AnnotationKind::UniqueLongestChoice)))
    {
        for issue in &advisory {
            out.push(Issue::new(
                "E-UNIQUE-LONGEST-LEFT-RECURSION",
                &issue.message,
                "The unique-longest profile supports acyclic left edges; rewrite the cycle.",
                issue.rule.as_deref(),
            ));
        }
    }
    out.extend(advisory);
    out
}
fn has(rule: &RuleDecl, test: impl Fn(&AnnotationKind) -> bool) -> bool {
    rule.annotations.iter().any(|a| test(&a.kind))
}
fn format_two(grammar: &GrammarDecl) -> bool {
    grammar
        .settings
        .iter()
        .any(|s| s.key == "ubnf" && matches!(&s.value, SettingValue::String(v) if v == "v2"))
}
fn whitespace(grammar: &GrammarDecl, style: &str) -> bool {
    // Java compiles definitions even for builtin policies; lexical errors invalidate both.
    crate::lexical::compile_syntax(grammar).is_ok_and(|definitions| {
        style.eq_ignore_ascii_case("javaStyle")
            || style.eq_ignore_ascii_case("none")
            || definitions.get(style).is_some_and(|e| !e.nullable())
    })
}
fn globals(grammar: &GrammarDecl, out: &mut Vec<Issue>) {
    let spaces: Vec<_> = grammar
        .settings
        .iter()
        .filter(|s| s.key == "whitespace")
        .collect();
    if spaces.len() > 1 {
        add(
            out,
            "E-WHITESPACE-GLOBAL-DUPLICATE",
            "duplicate global @whitespace settings",
            "Keep a single global @whitespace setting.",
        );
    }
    for setting in spaces {
        let message = match &setting.value {
            SettingValue::String(style) if whitespace(grammar, style.trim()) => continue,
            SettingValue::String(style) => format!("global @whitespace style must be javaStyle, none, or a non-nullable declarative token: {}", style.trim()),
            _ => "global @whitespace requires a style name".into(),
        };
        add(
            out,
            "E-WHITESPACE-GLOBAL-STYLE",
            message,
            "Use javaStyle, none, or the exact name of a non-nullable declarative token.",
        );
    }
    let versions: Vec<_> = grammar
        .settings
        .iter()
        .filter(|s| s.key == "ubnf")
        .collect();
    if versions.len() > 1 {
        add(
            out,
            "E-UBNF-VERSION-DUPLICATE",
            "duplicate global @ubnf setting",
            "Keep exactly one '@ubnf: v2' declaration.",
        );
    } else if versions
        .first()
        .is_some_and(|s| !matches!(&s.value, SettingValue::String(v) if v == "v1" || v == "v2"))
    {
        add(
            out,
            "E-UBNF-VERSION",
            "@ubnf must be format version v1 or v2",
            "Use '@ubnf: v2' for context-aware external token contracts.",
        );
    }
    let mut seen = vec![];
    for setting in grammar.settings.iter().filter(|s| s.key == "memoSafeToken") {
        let SettingValue::String(alias) = &setting.value else {
            add(
                out,
                "E-MEMO-SAFE-TOKEN-VALUE",
                "global @memoSafeToken requires a token alias string",
                "Use one setting per alias, for example '@memoSafeToken: IDENTIFIER'.",
            );
            continue;
        };
        let alias = alias.trim();
        if seen.contains(&alias) {
            add(
                out,
                "E-MEMO-SAFE-TOKEN-DUPLICATE",
                format!("duplicate global @memoSafeToken alias: {alias}"),
                format!("Keep a single @memoSafeToken setting for alias {alias}."),
            );
            continue;
        }
        seen.push(alias);
        match grammar.tokens.iter().find(|t| t.name == alias) {
            None => add(
                out,
                "E-MEMO-SAFE-TOKEN-UNDEFINED",
                format!("global @memoSafeToken references undefined token alias: {alias}"),
                format!("Declare token {alias} or remove this @memoSafeToken setting."),
            ),
            Some(token) => {
                let valid = match &token.kind {
                    TokenKind::Simple { .. } | TokenKind::Declarative { .. } => true,
                    TokenKind::Adapter { id, version } => matches!(
                        AdapterRegistry::builtins().resolve(id, version),
                        Ok(crate::adapters::AdapterBinding::Builtin(_))
                    ),
                    _ => false,
                };
                if !valid {
                    add(out, "E-MEMO-SAFE-TOKEN-KIND", format!("global @memoSafeToken alias must name a Simple or built-in Adapter token: {alias}"), "Remove the setting or use a Simple or built-in Adapter token.");
                }
            }
        }
    }
    let root = grammar
        .rules
        .iter()
        .any(|r| has(r, |a| matches!(a, AnnotationKind::Root)));
    let imported = !grammar.rules.is_empty() && grammar.rules.iter().all(|r| r.name.contains('.'));
    let library = grammar.rules.is_empty()
        && grammar
            .tokens
            .iter()
            .all(|t| matches!(t.kind, TokenKind::Declarative { .. }));
    if !(root || imported || library) {
        add(
            out,
            "W-GENERAL-NO-ROOT",
            format!("grammar {} has no @root rule", grammar.name),
            "Add @root to at least one entry rule.",
        );
    }
}
fn parser_name(token: &TokenDecl) -> Option<String> {
    match &token.kind {
        TokenKind::Simple { parser_class } if !parser_class.contains('.') => {
            return (!parser_class.trim().is_empty()).then(|| parser_class.clone())
        }
        TokenKind::Simple { .. }
        | TokenKind::Adapter { .. }
        | TokenKind::Declarative { .. }
        | TokenKind::Until { .. }
        | TokenKind::Negation { .. }
        | TokenKind::CharRange { .. }
        | TokenKind::Regex { .. }
        | TokenKind::CaseInsensitive { .. } => {}
        _ => return None,
    }
    let mut result = String::new();
    let mut upper = true;
    for c in token.name.chars() {
        if c == '_' {
            upper = true;
        } else {
            result.push(if upper {
                c.to_ascii_uppercase()
            } else {
                c.to_ascii_lowercase()
            });
            upper = false;
        }
    }
    result.push_str("Parser");
    Some(result)
}
fn tokens(grammar: &GrammarDecl, out: &mut Vec<Issue>) {
    // The bundled Java class candidates are fixed to the reference artifact, not
    // inferred from Rust's supported runtime tokens. Arbitrary classpath probes remain a gap.
    let classes: Vec<String> =
        serde_json::from_str(include_str!("java-classes.json")).expect("bundled Java class index");
    for token in &grammar.tokens {
        if let TokenKind::Simple { parser_class } = &token.kind {
            let parts: Vec<_> = parser_class.split('.').collect();
            let known = [
                "org.unlaxer.parser.clang.",
                "org.unlaxer.parser.elementary.",
                "org.unlaxer.parser.posix.",
            ]
            .iter()
            .any(|p| parser_class.starts_with(p));
            let fqn = parts.len() > 1
                && parts[..parts.len() - 1]
                    .iter()
                    .all(|p| p.starts_with(char::is_lowercase))
                && parts.last().unwrap().starts_with(char::is_uppercase);
            if !parser_class.is_empty() && !known && !fqn {
                let mut hint = String::new();
                if !parser_class.contains('.') {
                    let candidates: Vec<_> =
                        ["elementary", "posix", "clang", "combinator", "ascii"]
                            .iter()
                            .map(|p| format!("org.unlaxer.parser.{p}.{parser_class}"))
                            .filter(|p| classes.contains(p))
                            .collect();
                    if !candidates.is_empty() {
                        hint = format!("Did you mean '{}'? ", candidates.join("' or '"));
                    }
                }
                hint.push_str("Ensure the parser class is fully qualified or in a known package (org.unlaxer.parser.clang, elementary, posix).");
                add(
                    out,
                    "W-TOKEN-UNRESOLVED",
                    format!(
                        "token {} references unresolved parser class: {parser_class}",
                        token.name
                    ),
                    hint,
                );
            }
        }
    }
    for token in &grammar.tokens {
        if let Some(name) = parser_name(token) {
            if let Some(rule) = grammar
                .rules
                .iter()
                .rev()
                .find(|r| format!("{}Parser", r.name) == name)
            {
                out.push(Issue::new(
                    "E-RULE-TOKEN-NAME-COLLISION",
                    format!(
                        "rule {} and token {} both resolve to generated parser class {name}",
                        rule.name, token.name
                    ),
                    "Rename the rule or token so their generated parser class names differ.",
                    Some(&rule.name),
                ));
            }
        }
    }
    let mut first: Vec<(String, &TokenDecl)> = vec![];
    for token in &grammar.tokens {
        if !matches!(
            token.kind,
            TokenKind::Declarative { .. }
                | TokenKind::Adapter { .. }
                | TokenKind::Simple { .. }
                | TokenKind::Negation { .. }
                | TokenKind::CharRange { .. }
                | TokenKind::Regex { .. }
        ) {
            continue;
        }
        let Some(name) = parser_name(token) else {
            continue;
        };
        if let Some((_, previous)) = first.iter().find(|(n, _)| *n == name) {
            if [*previous, token].iter().any(|t| {
                matches!(
                    t.kind,
                    TokenKind::Adapter { .. } | TokenKind::Declarative { .. }
                )
            }) {
                add(out, "E-TOKEN-ADAPTER-NAME-COLLISION", format!("token {} and token {} both resolve to parser class {name}", previous.name, token.name), "Rename one token or use a distinct parser class so the adapter wrapper cannot shadow it.");
            }
        } else {
            first.push((name, token));
        }
    }
}

// The shared check API uses the written feature value as subject; Java's
// GrammarValidator uses "feature" for VERSION/UNKNOWN and gives VERSION priority.
// Adapt the authoring contract here without changing CLI diagnostic consumers.
fn feature_diagnostics(grammar: &GrammarDecl) -> Vec<crate::adapters::AdapterDiagnostic> {
    crate::adapters::feature_diagnostics(grammar)
        .into_iter()
        .map(|mut issue| {
            if !format_two(grammar) {
                issue.code = "E-FEATURE-VERSION";
            }
            if issue.code != "E-FEATURE-DUPLICATE" {
                issue.subject = "feature".into();
            }
            issue
        })
        .collect()
}
