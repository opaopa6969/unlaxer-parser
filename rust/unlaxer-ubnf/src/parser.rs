use crate::ast::*;
use crate::lexer::{Kind, Token};
use crate::lexical::{LexicalExpression as Lex, Op};
use crate::{diagnostic, Diagnostic, DiagnosticKind, MAX_NESTING};

type Result<T> = std::result::Result<T, Diagnostic>;

pub(crate) fn parse(source: &str, tokens: Vec<Token>) -> Result<UbnfFile> {
    let mut parser = Parser {
        source,
        scans: crate::params::prescan(&tokens),
        parameterized: Default::default(),
        tokens,
        pos: 0,
        depth: 0,
        calls: Vec::new(),
        rule_params: Vec::new(),
    };
    let mut grammars = Vec::new();
    while parser.current().kind != Kind::Eof {
        grammars.push(parser.grammar()?);
    }
    if grammars.is_empty() {
        return Err(parser.error("expected at least one grammar"));
    }
    let span = Span::default().through(parser.current().span);
    // Expansion follows the complete syntax parse, as in the Java frontend.
    let grammars = grammars
        .into_iter()
        .zip(std::mem::take(&mut parser.rule_params))
        .map(|(grammar, params)| crate::params::expand(source, grammar, params, &parser.calls))
        .collect::<Result<Vec<_>>>()?;
    Ok(UbnfFile { grammars, span })
}

struct Parser<'a> {
    source: &'a str,
    tokens: Vec<Token>,
    pos: usize,
    depth: usize,
    /// Per grammar in source order: rules defined as `Name(params) ::=`.
    scans: Vec<std::collections::HashSet<String>>,
    /// The entry of `scans` for the grammar being parsed.
    parameterized: std::collections::HashSet<String>,
    calls: Vec<crate::params::Call>,
    rule_params: Vec<Vec<Option<Vec<crate::params::Param>>>>,
}

impl Parser<'_> {
    fn current(&self) -> &Token {
        &self.tokens[self.pos]
    }
    fn previous(&self) -> Span {
        self.tokens[self.pos.saturating_sub(1)].span
    }
    fn span_from(&self, start: Span) -> Span {
        start.through(self.previous())
    }
    fn error(&self, message: impl Into<String>) -> Diagnostic {
        diagnostic(
            self.source,
            self.current().span,
            DiagnosticKind::Syntax,
            message,
        )
    }
    fn invalid(&self, span: Span, message: impl Into<String>) -> Diagnostic {
        diagnostic(self.source, span, DiagnosticKind::InvalidValue, message)
    }
    fn is(&self, symbol: char) -> bool {
        self.current().kind == Kind::Symbol(symbol)
    }
    fn eat(&mut self, symbol: char) -> bool {
        if self.is(symbol) {
            self.pos += 1;
            true
        } else {
            false
        }
    }
    fn expect(&mut self, symbol: char) -> Result<()> {
        if self.eat(symbol) {
            Ok(())
        } else {
            Err(self.error(format!("expected {symbol:?}")))
        }
    }
    fn word_is(&self, word: &str) -> bool {
        matches!(&self.current().kind, Kind::Identifier(s) if s == word)
    }
    fn word(&mut self, word: &str) -> Result<()> {
        if self.word_is(word) {
            self.pos += 1;
            Ok(())
        } else {
            Err(self.error(format!("expected {word}")))
        }
    }
    fn identifier(&mut self) -> Result<String> {
        if let Kind::Identifier(value) = &self.current().kind {
            let value = value.clone();
            self.pos += 1;
            Ok(value)
        } else {
            Err(self.error("expected ASCII identifier"))
        }
    }
    fn quoted(&mut self) -> Result<String> {
        if let Kind::Quoted(value) = &self.current().kind {
            let value = value.clone();
            self.pos += 1;
            Ok(value)
        } else {
            Err(self.error("expected single-quoted literal"))
        }
    }
    fn class_quoted(&mut self) -> Result<String> {
        let span = self.current().span;
        self.quoted()?;
        crate::codepoint_escapes::decode(&self.source[span.byte_start..span.byte_end])
            .map_err(|error| self.invalid(span, error))
    }
    // Syntax accepts every ASCII digit run; validation owns the signed-32-bit limit.
    fn repeat_bound(&mut self) -> Result<u32> {
        if let Kind::Number(value) = &self.current().kind {
            let number = value
                .parse::<u32>()
                .unwrap_or(u32::MAX)
                .min(i32::MAX as u32 + 1);
            self.pos += 1;
            Ok(number)
        } else {
            Err(self.error("expected unsigned ASCII integer"))
        }
    }
    fn dotted(&mut self) -> Result<String> {
        let mut value = self.identifier()?;
        while self.eat('.') {
            value.push('.');
            value.push_str(&self.identifier()?);
        }
        Ok(value)
    }
    fn annotation_is(&self, name: &str) -> bool {
        self.is('@')
            && self
                .tokens
                .get(self.pos + 1)
                .is_some_and(|t| matches!(&t.kind, Kind::Identifier(s) if s == name))
    }
    fn setting_ahead(&self) -> bool {
        self.is('@')
            && self
                .tokens
                .get(self.pos + 2)
                .is_some_and(|t| t.kind == Kind::Symbol(':'))
    }
    fn grammar(&mut self) -> Result<GrammarDecl> {
        self.parameterized = self
            .scans
            .get(self.rule_params.len())
            .cloned()
            .unwrap_or_default();
        let start = self.current().span;
        self.word("grammar")?;
        let name = self.identifier()?;
        self.expect('{')?;
        let mut imports = Vec::new();
        while self.annotation_is("import") {
            let start = self.current().span;
            self.pos += 2;
            let alias = self.identifier()?;
            self.word("from")?;
            let path = self.quoted()?;
            imports.push(ImportDecl {
                alias,
                path,
                span: self.span_from(start),
            });
        }
        let mut settings = Vec::new();
        while self.setting_ahead() {
            let start = self.current().span;
            self.pos += 1;
            let key = self.identifier()?;
            self.expect(':')?;
            let value_start = self.current().span;
            let value = if self.eat('{') {
                let mut entries = Vec::new();
                while !self.is('}') {
                    let start = self.current().span;
                    let key = self.identifier()?;
                    self.expect(':')?;
                    let value = self.quoted()?;
                    entries.push(KeyValuePair {
                        key,
                        value,
                        span: self.span_from(start),
                    });
                }
                self.expect('}')?;
                SettingValue::Block(entries)
            } else if self.current().kind == Kind::Number("2".into()) {
                self.pos += 1;
                SettingValue::String(if key == "ubnf" { "v2" } else { "2" }.into())
            } else {
                SettingValue::String(self.dotted()?)
            };
            settings.push(GlobalSetting {
                key,
                value,
                value_span: self.span_from(value_start),
                span: self.span_from(start),
            });
        }
        let mut tokens = Vec::new();
        // A rule may itself be called token. Distinguish the complete declaration prefix.
        while self.word_is("token")
            && self
                .tokens
                .get(self.pos + 1)
                .is_some_and(|t| matches!(t.kind, Kind::Identifier(_)))
        {
            tokens.push(self.token_decl(settings.iter().any(|setting| {
                setting.key == "ubnf"
                    && matches!(&setting.value, SettingValue::String(value) if value == "v2")
            }))?);
        }
        let mut rules = Vec::new();
        let mut rule_params = Vec::new();
        while !self.is('}') && self.current().kind != Kind::Eof {
            let (rule, params) = self.rule()?;
            rules.push(rule);
            rule_params.push(params);
        }
        self.expect('}')?;
        self.rule_params.push(rule_params);
        Ok(GrammarDecl {
            name,
            imports,
            settings,
            tokens,
            rules,
            span: self.span_from(start),
        })
    }
    fn token_decl(&mut self, format_two: bool) -> Result<TokenDecl> {
        let start = self.current().span;
        self.word("token")?;
        let name = self.identifier()?;
        if self.current().kind == Kind::Define {
            self.pos += 1;
            let expression = self.lex_expression()?;
            self.expect(';')?;
            return Ok(TokenDecl {
                name,
                kind: TokenKind::Declarative { expression },
                span: self.span_from(start),
            });
        }
        self.expect('=')?;
        let value_start = self.current().span;
        let value = self.dotted()?;
        if (value == "REGEX" && self.is('('))
            || value == "RegexTokenParser"
            || value == "org.unlaxer.dsl.runtime.RegexTokenParser"
        {
            return Err(self.invalid(value_start.through(self.previous()),
                "E-REGEX-RETIRED: REGEX execution was removed; use token NAME ::= expression; (see docs/regex-retirement.md)"));
        }
        let kind = match value.as_str() {
            "ANY" => TokenKind::Any,
            "XID_IDENTIFIER" => TokenKind::Declarative {
                expression: crate::lexical::LexicalExpression::leaf(
                    crate::lexical::Op::XID_IDENTIFIER,
                    String::new(),
                ),
            },
            "EOF" => TokenKind::Eof,
            "EMPTY" => TokenKind::Empty,
            "UNTIL" | "NEGATION" | "LOOKAHEAD" | "NEGATIVE_LOOKAHEAD" | "CI" if self.is('(') => {
                self.expect('(')?;
                let arg = if format_two && value == "NEGATION" {
                    self.class_quoted()?
                } else {
                    self.quoted()?
                };
                self.expect(')')?;
                match value.as_str() {
                    "UNTIL" => TokenKind::Until { terminator: arg },
                    "NEGATION" => TokenKind::Negation {
                        excluded_chars: arg,
                    },
                    "LOOKAHEAD" => TokenKind::Lookahead { pattern: arg },
                    "NEGATIVE_LOOKAHEAD" => TokenKind::NegativeLookahead { pattern: arg },
                    "CI" => TokenKind::CaseInsensitive { word: arg },
                    _ => unreachable!(),
                }
            }
            "CHAR_RANGE" if self.is('(') => {
                self.expect('(')?;
                let a = self.current().span;
                let min = if format_two {
                    self.class_quoted()?
                } else {
                    self.quoted()?
                };
                self.expect(',')?;
                let b = self.current().span;
                let max = if format_two {
                    self.class_quoted()?
                } else {
                    self.quoted()?
                };
                self.expect(')')?;
                let min = if format_two {
                    crate::codepoint_escapes::boundary(&min)
                        .map_err(|error| self.invalid(a, error))?
                } else {
                    self.bmp(&min, a)?
                };
                let max = if format_two {
                    crate::codepoint_escapes::boundary(&max)
                        .map_err(|error| self.invalid(b, error))?
                } else {
                    self.bmp(&max, b)?
                };
                if format_two {
                    crate::codepoint_escapes::validate_range(min, max)
                        .map_err(|error| self.invalid(a, error))?;
                } else if min > max {
                    return Err(self.invalid(value_start, "CHAR_RANGE minimum exceeds maximum"));
                }
                if min as u32 > 0xffff || max as u32 > 0xffff {
                    TokenKind::Declarative {
                        expression: Lex {
                            min: min as i32,
                            max: max as i32,
                            ..Lex::leaf(Op::RANGE, String::new())
                        },
                    }
                } else {
                    TokenKind::CharRange { min, max }
                }
            }
            "ADAPTER" if self.is('(') => {
                self.expect('(')?;
                let id = self.quoted()?;
                self.expect(',')?;
                self.word("version")?;
                self.expect('=')?;
                let version = if let Kind::Number(value) = &self.current().kind {
                    let value = value.clone();
                    self.pos += 1;
                    value
                } else {
                    return Err(self.error("expected adapter version integer"));
                };
                self.expect(')')?;
                TokenKind::Adapter { id, version }
            }
            _ => {
                if self.is('(') {
                    return Err(diagnostic(
                        self.source,
                        value_start,
                        DiagnosticKind::UnsupportedSyntax,
                        format!("unknown token constructor {value}"),
                    ));
                }
                TokenKind::Simple {
                    parser_class: value,
                }
            }
        };
        Ok(TokenDecl {
            name,
            kind,
            span: self.span_from(start),
        })
    }
    fn lex_expression(&mut self) -> Result<Lex> {
        self.depth += 1;
        if self.depth > MAX_NESTING {
            return Err(self.error("lexical nesting exceeds 128"));
        }
        let mut alternatives = vec![self.lex_sequence()?];
        while self.eat('|') {
            alternatives.push(self.lex_sequence()?);
        }
        self.depth -= 1;
        Ok(Lex::node(Op::CHOICE, alternatives))
    }
    fn lex_sequence(&mut self) -> Result<Lex> {
        let mut children = vec![];
        while matches!(
            self.current().kind,
            Kind::Quoted(_) | Kind::Identifier(_) | Kind::Symbol('(' | '[' | '{')
        ) {
            children.push(self.lex_element()?);
        }
        if children.is_empty() {
            return Err(self.error("empty lexical sequence"));
        }
        Ok(Lex::node(Op::SEQUENCE, children))
    }
    fn lex_integer(&mut self) -> Result<i32> {
        if let Kind::Number(value) = &self.current().kind {
            let value = value
                .parse::<i32>()
                .map_err(|_| self.error("repeat bound exceeds 2147483647"))?;
            self.pos += 1;
            Ok(value)
        } else {
            Err(self.error("expected repeat bound"))
        }
    }
    fn lex_element(&mut self) -> Result<Lex> {
        let child = self.lex_atom()?;
        if self.eat('?') {
            return Ok(Lex::repeat(child, 0, 1));
        }
        if self.eat('*') {
            return Ok(Lex::repeat(child, 0, -1));
        }
        if self.eat('+') {
            return Ok(Lex::repeat(child, 1, -1));
        }
        if self.is('{')
            && self
                .tokens
                .get(self.pos + 1)
                .is_some_and(|t| matches!(t.kind, Kind::Number(_)))
        {
            self.pos += 1;
            let min = self.lex_integer()?;
            let max = if self.eat(',') {
                if self.is('}') {
                    -1
                } else {
                    self.lex_integer()?
                }
            } else {
                min
            };
            self.expect('}')?;
            if max >= 0 && min > max {
                return Err(self.error("inverted repeat bounds"));
            }
            return Ok(Lex::repeat(child, min, max));
        }
        Ok(child)
    }
    fn lex_atom(&mut self) -> Result<Lex> {
        if matches!(self.current().kind, Kind::Quoted(_)) {
            return Ok(Lex::leaf(Op::LITERAL, self.quoted()?));
        }
        if self.eat('(') {
            let child = self.lex_expression()?;
            self.expect(')')?;
            return Ok(child);
        }
        if self.eat('[') {
            let child = self.lex_expression()?;
            self.expect(']')?;
            return Ok(Lex::repeat(child, 0, 1));
        }
        if self.eat('{') {
            let child = self.lex_expression()?;
            self.expect('}')?;
            return Ok(Lex::repeat(child, 0, -1));
        }
        let mut name = self.identifier()?;
        if matches!(
            name.as_str(),
            "LOOKAHEAD" | "NEGATIVE_LOOKAHEAD" | "CAPTURE" | "SAME_AS" | "NEGATION" | "CHAR_RANGE"
        ) && self.eat('(')
        {
            let result = match name.as_str() {
                "LOOKAHEAD" | "NEGATIVE_LOOKAHEAD" => Lex::node(
                    if name == "LOOKAHEAD" {
                        Op::LOOK
                    } else {
                        Op::NOT
                    },
                    vec![self.lex_expression()?],
                ),
                "CAPTURE" => {
                    let text = self.identifier()?;
                    self.expect(',')?;
                    Lex {
                        text,
                        ..Lex::node(Op::CAPTURE, vec![self.lex_expression()?])
                    }
                }
                "SAME_AS" => Lex::leaf(Op::BACKREF, self.identifier()?),
                "NEGATION" => Lex::leaf(Op::EXCEPT, self.class_quoted()?),
                "CHAR_RANGE" => {
                    let span = self.current().span;
                    let a = self.class_quoted()?;
                    let min = crate::codepoint_escapes::boundary(&a)
                        .map_err(|error| self.invalid(span, error))?;
                    self.expect(',')?;
                    let max_span = self.current().span;
                    let b = self.class_quoted()?;
                    let max = crate::codepoint_escapes::boundary(&b)
                        .map_err(|error| self.invalid(max_span, error))?;
                    crate::codepoint_escapes::validate_range(min, max)
                        .map_err(|error| self.invalid(span, error))?;
                    Lex {
                        min: min as i32,
                        max: max as i32,
                        ..Lex::leaf(Op::RANGE, String::new())
                    }
                }
                _ => return Err(self.error(format!("unknown lexical constructor {name}"))),
            };
            self.expect(')')?;
            return Ok(result);
        }
        let op = match name.as_str() {
            "ANY" => Some(Op::ANY),
            "XID_IDENTIFIER" => Some(Op::XID_IDENTIFIER),
            "EOF" => Some(Op::EOF),
            "BOF" => Some(Op::BOF),
            "BOL" => Some(Op::BOL),
            "EOL" => Some(Op::EOL),
            _ => None,
        };
        if let Some(op) = op {
            return Ok(Lex::leaf(op, String::new()));
        }
        while self.eat('.') {
            name.push('.');
            name.push_str(&self.identifier()?);
        }
        Ok(Lex::leaf(Op::REF, name))
    }
    fn bmp(&self, value: &str, span: Span) -> Result<char> {
        let mut chars = value.chars();
        match (chars.next(), chars.next()) {
            (Some(c), None) if c as u32 <= 0xffff => Ok(c),
            _ => Err(self.invalid(
                span,
                "CHAR_RANGE boundary requires exactly one non-surrogate BMP character",
            )),
        }
    }
    /// True when the current token is '(' immediately after the previous token.
    fn adjacent_open(&self) -> bool {
        self.is('(') && self.previous().byte_end == self.current().span.byte_start
    }
    fn rule(&mut self) -> Result<(RuleDecl, Option<Vec<crate::params::Param>>)> {
        let start = self.current().span;
        let mut annotations = Vec::new();
        while self.is('@') {
            annotations.push(self.annotation()?);
        }
        let name = self.identifier()?;
        let params = if self.adjacent_open() {
            self.pos += 1;
            let params = self.parameters()?;
            self.expect(')')?;
            Some(params)
        } else {
            None
        };
        if self.current().kind != Kind::Define {
            return Err(self.error("expected ::="));
        }
        self.pos += 1;
        let body = self.body()?;
        self.expect(';')?;
        Ok((
            RuleDecl {
                annotations,
                name,
                body,
                span: self.span_from(start),
            },
            params,
        ))
    }
    /// `p1, =p2, ...`: a leading `=` marks a value parameter (#704).
    fn parameters(&mut self) -> Result<Vec<crate::params::Param>> {
        let mut values = Vec::new();
        loop {
            let by_value = self.eat('=');
            values.push(crate::params::Param {
                name: self.identifier()?,
                by_value,
            });
            if !self.eat(',') {
                return Ok(values);
            }
        }
    }
    fn named_arg(&mut self, name: &str) -> Result<()> {
        self.word(name)?;
        self.expect('=')
    }
    fn identifiers(&mut self) -> Result<Vec<String>> {
        let mut values = vec![self.identifier()?];
        while self.eat(',') {
            values.push(self.identifier()?);
        }
        Ok(values)
    }
    fn annotation(&mut self) -> Result<Annotation> {
        let start = self.current().span;
        self.expect('@')?;
        // Java's annotation keywords are single words (`@leftAssoc`, `@mapping(`), while its
        // catch-all is `'@' IDENTIFIER` with ordinary trivia between: `@ leftAssoc` and an
        // argument keyword without its `(` are Simple annotations there (#715).
        let spaced = self.current().span.byte_start != self.previous().byte_end;
        let name = self.identifier()?;
        let bare = !self.is('(')
            && matches!(
                name.as_str(),
                "mapping"
                    | "eval"
                    | "lexicalContext"
                    | "interleave"
                    | "backref"
                    | "scopeTree"
                    | "declares"
                    | "catalog"
                    | "precedence"
                    | "doc"
                    | "recovery"
                    | "commonField"
            );
        let kind = match name.as_str() {
            _ if spaced || bare => self.simple_annotation(start, name)?,
            "root" => AnnotationKind::Root,
            "leftAssoc" => AnnotationKind::LeftAssoc,
            "rightAssoc" => AnnotationKind::RightAssoc,
            "longestChoice" => AnnotationKind::LongestChoice,
            "uniqueLongestChoice" => AnnotationKind::UniqueLongestChoice,
            "namePredicate" => {
                self.expect('(')?;
                self.named_arg("snapshot")?;
                let snapshot = self.quoted()?;
                self.expect(',')?;
                self.named_arg("version")?;
                let version = self.quoted()?;
                self.expect(',')?;
                self.named_arg("name")?;
                let name = self.quoted()?;
                self.expect(',')?;
                self.named_arg("kind")?;
                let kind = self.quoted()?;
                self.expect(')')?;
                AnnotationKind::NamePredicate {
                    snapshot,
                    version,
                    name,
                    kind,
                }
            }
            "predictiveChoice" => AnnotationKind::PredictiveChoice,
            "skip" => AnnotationKind::Skip,
            "enum" => AnnotationKind::Enum,
            "mapping" => {
                self.expect('(')?;
                let class_name = self.dotted()?;
                let params = if self.eat(',') {
                    self.named_arg("params")?;
                    self.expect('[')?;
                    let params = self.identifiers()?;
                    self.expect(']')?;
                    params
                } else {
                    Vec::new()
                };
                self.expect(')')?;
                AnnotationKind::Mapping { class_name, params }
            }
            "eval" => {
                self.expect('(')?;
                self.named_arg("kind")?;
                let kind = self.quoted()?;
                let mut strategy = None;
                let mut params: Vec<KeyValuePair> = Vec::new();
                while self.eat(',') {
                    let arg_start = self.current().span;
                    let key = self.identifier()?;
                    self.expect('=')?;
                    let value = self.quoted()?;
                    if key == "kind"
                        || params.iter().any(|p| p.key == key)
                        || key == "strategy" && strategy.is_some()
                    {
                        return Err(
                            self.invalid(arg_start, format!("duplicate @eval argument {key}"))
                        );
                    }
                    if key == "strategy" {
                        strategy = Some(value);
                    } else {
                        params.push(KeyValuePair {
                            key,
                            value,
                            span: self.span_from(arg_start),
                        });
                    }
                }
                self.expect(')')?;
                AnnotationKind::Eval {
                    kind,
                    strategy: strategy.unwrap_or_else(|| "default".to_owned()),
                    params,
                }
            }
            "whitespace" => {
                let style = if self.eat('(') {
                    let value = self.dotted()?;
                    self.expect(')')?;
                    Some(value)
                } else {
                    None
                };
                AnnotationKind::Whitespace { style }
            }
            "lexicalContext" => {
                self.expect('(')?;
                self.named_arg("tokens")?;
                let tokens = self.lexical_context_list()?;
                let literals = if self.eat(',') {
                    self.named_arg("literals")?;
                    self.lexical_context_list()?
                } else {
                    vec![]
                };
                self.expect(')')?;
                AnnotationKind::LexicalContext { tokens, literals }
            }
            "interleave" | "backref" | "scopeTree" => {
                self.expect('(')?;
                self.named_arg(match name.as_str() {
                    "interleave" => "profile",
                    "backref" => "name",
                    _ => "mode",
                })?;
                let value = self.identifier()?;
                self.expect(')')?;
                match name.as_str() {
                    "interleave" => AnnotationKind::Interleave { profile: value },
                    "backref" => AnnotationKind::Backref { name: value },
                    _ => AnnotationKind::ScopeTree { mode: value },
                }
            }
            "declares" => {
                self.expect('(')?;
                self.named_arg("symbol")?;
                let symbol_capture = self.identifier()?;
                let description = if self.eat(',') {
                    self.named_arg("description")?;
                    Some(self.identifier()?)
                } else {
                    None
                };
                self.expect(')')?;
                AnnotationKind::Declares {
                    symbol_capture,
                    description,
                }
            }
            "catalog" => {
                self.expect('(')?;
                self.named_arg("context")?;
                let context = self.quoted()?;
                self.expect(')')?;
                AnnotationKind::Catalog { context }
            }
            "precedence" => {
                self.expect('(')?;
                self.named_arg("level")?;
                // MAX+1 becomes i32::MIN: transport overflow to semantic validation.
                let level = self.repeat_bound()? as i32;
                self.expect(')')?;
                AnnotationKind::Precedence { level }
            }
            "doc" => {
                self.expect('(')?;
                let text = self.quoted()?;
                self.expect(')')?;
                AnnotationKind::Doc { text }
            }
            "recovery" => {
                self.expect('(')?;
                let mode_start = self.current().span;
                let mode = self.identifier()?;
                let (mode, sync_tokens) = match mode.as_str() {
                    "auto" => (RecoveryMode::Auto, Vec::new()),
                    "skip" => (RecoveryMode::Skip, Vec::new()),
                    "sync" => {
                        self.expect('=')?;
                        let tokens = self
                            .quoted()?
                            .split(',')
                            // UBNFMapper splits sync lists using Java String.trim(),
                            // not Unicode whitespace (NBSP and EM SPACE are tokens).
                            .map(|token| token.trim_matches(|ch| ch <= '\u{0020}'))
                            .filter(|s| !s.is_empty())
                            .map(str::to_owned)
                            .collect();
                        (RecoveryMode::Sync, tokens)
                    }
                    _ => {
                        return Err(
                            self.invalid(mode_start, "expected recovery mode auto, skip or sync")
                        )
                    }
                };
                self.expect(')')?;
                AnnotationKind::Recovery { mode, sync_tokens }
            }
            "commonField" => {
                self.expect('(')?;
                let fields = self.identifiers()?;
                self.expect(')')?;
                AnnotationKind::CommonField { fields }
            }
            _ => self.simple_annotation(start, name)?,
        };
        Ok(Spanned {
            kind,
            span: self.span_from(start),
        })
    }
    fn simple_annotation(&self, start: Span, name: String) -> Result<AnnotationKind> {
        if self.is('(') {
            return Err(diagnostic(
                self.source,
                self.span_from(start),
                DiagnosticKind::UnsupportedSyntax,
                format!("unknown parameterized annotation @{name}"),
            ));
        }
        Ok(AnnotationKind::Simple { name })
    }
    fn body(&mut self) -> Result<RuleBody> {
        if self.depth >= MAX_NESTING {
            return Err(diagnostic(
                self.source,
                self.current().span,
                DiagnosticKind::NestingLimit,
                "UBNF nesting limit exceeded",
            ));
        }
        self.depth += 1;
        let start = self.current().span;
        let mut alternatives = vec![self.sequence()?];
        while self.eat('|') {
            alternatives.push(self.sequence()?);
        }
        self.depth -= 1;
        Ok(RuleBody {
            alternatives,
            span: self.span_from(start),
        })
    }
    fn sequence(&mut self) -> Result<Sequence> {
        let start = self.current().span;
        let mut elements = Vec::new();
        while matches!(
            self.current().kind,
            Kind::Identifier(_) | Kind::Quoted(_) | Kind::Symbol('(' | '[' | '{' | '@')
        ) {
            elements.push(self.element()?);
        }
        if elements.is_empty() {
            return Err(
                self.error("expected nonempty rule sequence (use an EMPTY token for epsilon)")
            );
        }
        Ok(Sequence {
            elements,
            span: self.span_from(start),
        })
    }
    // Only `atomic` recurses. The prefix, postfix and capture parsing run in their own
    // frames, so a debug build keeps one small frame per level and reaches the nesting
    // limit within a 2 MiB stack (#805).
    fn element(&mut self) -> Result<AnnotatedElement> {
        let start = self.current().span;
        let prefix = self.typeof_prefix(start)?;
        let element = self.atomic()?;
        // The postfix operators below are alternatives of one token, so testing '%'
        // first keeps the original precedence.
        let element = if self.eat('%') {
            let separator = self.atomic()?;
            self.separated(element, separator)
        } else {
            self.postfix(element)?
        };
        self.annotated(start, prefix, element)
    }

    #[inline(never)]
    fn typeof_prefix(&mut self, start: Span) -> Result<(Option<String>, Option<Span>)> {
        if !self.annotation_is("typeof") {
            return Ok((None, None));
        }
        self.pos += 2;
        self.expect('(')?;
        let name = self.identifier()?;
        self.expect(')')?;
        Ok((Some(name), Some(self.span_from(start))))
    }

    #[inline(never)]
    fn separated(&self, element: AtomicElement, separator: AtomicElement) -> AtomicElement {
        let element_start = element.span;
        Spanned {
            kind: ElementKind::Separated {
                element: Box::new(element),
                separator: Box::new(separator),
            },
            span: self.span_from(element_start),
        }
    }

    #[inline(never)]
    fn postfix(&mut self, mut element: AtomicElement) -> Result<AtomicElement> {
        let element_start = element.span;
        if self.eat('+') {
            element = Spanned {
                kind: ElementKind::OneOrMore(Box::new(element)),
                span: self.span_from(element_start),
            };
        } else if self.is('?') || self.is('*') {
            let optional = self.eat('?');
            if !optional {
                self.expect('*')?;
            }
            let body = RuleBody {
                span: element.span,
                alternatives: vec![Sequence {
                    span: element.span,
                    elements: vec![AnnotatedElement {
                        span: element.span,
                        element,
                        capture: None,
                        typeof_constraint: None,
                        capture_span: None,
                        typeof_span: None,
                    }],
                }],
            };
            element = Spanned {
                kind: if optional {
                    ElementKind::Optional(body)
                } else {
                    ElementKind::Repeat(body)
                },
                span: self.span_from(element_start),
            };
        } else if self.is('{')
            && self
                .tokens
                .get(self.pos + 1)
                .is_some_and(|t| matches!(t.kind, Kind::Number(_)))
        {
            self.pos += 1;
            let min = self.repeat_bound()?;
            let max = if self.eat(',') {
                if self.is('}') {
                    None
                } else {
                    Some(self.repeat_bound()?)
                }
            } else {
                Some(min)
            };
            self.expect('}')?;
            element = Spanned {
                kind: ElementKind::BoundedRepeat {
                    element: Box::new(element),
                    min,
                    max,
                },
                span: self.span_from(element_start),
            };
        }
        Ok(element)
    }

    #[inline(never)]
    fn annotated(
        &mut self,
        start: Span,
        (typeof_constraint, typeof_span): (Option<String>, Option<Span>),
        element: AtomicElement,
    ) -> Result<AnnotatedElement> {
        // @typeof belongs to the next element, not to a capture named "typeof".
        let mut capture_span = None;
        let capture = if self.is('@')
            && !(self.annotation_is("typeof")
                && self
                    .tokens
                    .get(self.pos + 2)
                    .is_some_and(|t| t.kind == Kind::Symbol('(')))
        {
            let capture_start = self.current().span;
            self.pos += 1;
            let name = self.identifier()?;
            capture_span = Some(self.span_from(capture_start));
            Some(name)
        } else {
            None
        };
        Ok(AnnotatedElement {
            element,
            capture,
            typeof_constraint,
            capture_span,
            typeof_span,
            span: self.span_from(start),
        })
    }
    fn lexical_context_list(&mut self) -> Result<Vec<String>> {
        self.expect('[')?;
        let mut values = vec![];
        if !self.is(']') {
            values.push(self.quoted()?);
            while self.eat(',') {
                values.push(self.quoted()?);
            }
        }
        self.expect(']')?;
        Ok(values)
    }

    // Builtin calls stay out of the recursive atomic() frame, so deep nesting reaches
    // the nesting-limit diagnostic instead of the thread's stack limit. Each operation
    // parses in its own frame and the arms share one result, so the frame kept per
    // nesting level stays small in debug builds (#805).
    #[inline(never)]
    fn builtin_call(&mut self) -> Result<AtomicElement> {
        let start = self.current().span;
        let operation = self.identifier()?;
        self.expect('(')?;
        let kind = match operation.as_str() {
            "CAPTURE" => self.owned_capture_call(),
            "SAME_AS" => self.replay_call(),
            "CAPTURE_SCOPE" => self.body().map(ElementKind::CaptureScope),
            "COMMIT" => self.commit_call(),
            "LABEL" => self.label_call(),
            "CATCH" => self.catch_call(),
            _ => unreachable!(),
        }?;
        self.expect(')')?;
        Ok(Spanned {
            kind,
            span: self.span_from(start),
        })
    }

    #[inline(never)]
    fn owned_capture_call(&mut self) -> Result<ElementKind> {
        let name = self.identifier()?;
        self.expect(',')?;
        Ok(ElementKind::OwnedCapture {
            name,
            body: self.body()?,
        })
    }

    #[inline(never)]
    fn replay_call(&mut self) -> Result<ElementKind> {
        Ok(ElementKind::Replay {
            name: self.identifier()?,
        })
    }

    #[inline(never)]
    fn commit_call(&mut self) -> Result<ElementKind> {
        let body = self.body()?;
        let message = if self.eat(',') {
            Some(self.quoted()?)
        } else {
            None
        };
        Ok(ElementKind::Commit { body, message })
    }

    #[inline(never)]
    fn label_call(&mut self) -> Result<ElementKind> {
        let label = self.identifier()?;
        self.expect(',')?;
        Ok(ElementKind::Label {
            label,
            body: self.body()?,
        })
    }

    #[inline(never)]
    fn catch_call(&mut self) -> Result<ElementKind> {
        let body = self.body()?;
        self.expect(',')?;
        let alternative = self.body()?;
        let mut labels = Vec::new();
        while self.eat(',') {
            labels.push(self.identifier()?);
        }
        Ok(crate::ast::catch_element(body, alternative, labels))
    }

    /// `Name(` where Name has a `Name(params) ::=` definition in this file. Builtin
    /// spellings keep their own syntax; their definitions are rejected after parsing.
    #[inline(never)]
    fn at_parameterized_call(&self) -> bool {
        matches!(&self.current().kind, Kind::Identifier(name)
        if self.parameterized.contains(name)
            && !matches!(
                name.as_str(),
                "CAPTURE" | "SAME_AS" | "CAPTURE_SCOPE" | "COMMIT" | "LABEL" | "CATCH" | "ERROR"
            ))
            && self.tokens.get(self.pos + 1).is_some_and(|t| {
                t.kind == Kind::Symbol('(') && self.current().span.byte_end == t.span.byte_start
            })
    }

    /// Kept out of `atomic` so its recursive frame does not grow (debug nesting budget).
    #[inline(never)]
    fn parameterized_call(&mut self) -> Result<AtomicElement> {
        let start = self.current().span;
        let name = self.identifier()?;
        self.expect('(')?;
        let mut args = vec![self.body()?];
        while self.eat(',') {
            args.push(self.body()?);
        }
        self.expect(')')?;
        let index = self.calls.len();
        self.calls.push(crate::params::Call {
            name,
            args,
            span: self.span_from(start),
        });
        Ok(Spanned {
            kind: ElementKind::RuleRef {
                namespace: None,
                name: crate::params::placeholder(index),
            },
            span: self.span_from(start),
        })
    }

    // Recursive forms leave this frame before any leaf parsing (#805).
    fn atomic(&mut self) -> Result<AtomicElement> {
        if self.at_parameterized_call() {
            return self.parameterized_call();
        }
        if self.at_builtin_call() {
            return self.builtin_call();
        }
        if matches!(self.current().kind, Kind::Symbol('(' | '[' | '{')) {
            return self.bracketed();
        }
        self.leaf()
    }

    #[inline(never)]
    fn at_builtin_call(&self) -> bool {
        matches!(&self.current().kind, Kind::Identifier(name)
            if matches!(name.as_str(), "CAPTURE" | "SAME_AS" | "CAPTURE_SCOPE" | "COMMIT" | "LABEL" | "CATCH"))
            && self.tokens.get(self.pos + 1).is_some_and(|t| {
                t.kind == Kind::Symbol('(') && self.current().span.byte_end == t.span.byte_start
            })
    }

    #[inline(never)]
    fn bracketed(&mut self) -> Result<AtomicElement> {
        let start = self.current().span;
        let (close, kind): (char, fn(RuleBody) -> ElementKind) = if self.eat('(') {
            (')', ElementKind::Group)
        } else if self.eat('[') {
            (']', ElementKind::Optional)
        } else {
            self.expect('{')?;
            ('}', ElementKind::Repeat)
        };
        let body = self.body()?;
        self.expect(close)?;
        Ok(Spanned {
            kind: kind(body),
            span: self.span_from(start),
        })
    }

    #[inline(never)]
    fn leaf(&mut self) -> Result<AtomicElement> {
        let start = self.current().span;
        let kind = if matches!(self.current().kind, Kind::Quoted(_)) {
            ElementKind::Terminal(self.quoted()?)
        } else if self.word_is("ERROR")
            && self
                .tokens
                .get(self.pos + 1)
                .is_some_and(|t| t.kind == Kind::Symbol('('))
            && self
                .tokens
                .get(self.pos + 2)
                .is_some_and(|t| matches!(t.kind, Kind::Quoted(_)))
            && self
                .tokens
                .get(self.pos + 3)
                .is_some_and(|t| t.kind == Kind::Symbol(')'))
        {
            self.pos += 1;
            self.expect('(')?;
            let message = self.quoted()?;
            self.expect(')')?;
            ElementKind::Error(message)
        } else {
            let value = self.dotted()?;
            let (namespace, name) = match value.rsplit_once('.') {
                Some((ns, name)) => (Some(ns.to_owned()), name.to_owned()),
                None => (None, value),
            };
            ElementKind::RuleRef { namespace, name }
        };
        Ok(Spanned {
            kind,
            span: self.span_from(start),
        })
    }
}
