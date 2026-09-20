use crate::ast::*;
use crate::lexer::{Kind, Token};
use crate::{diagnostic, Diagnostic, DiagnosticKind, MAX_NESTING};

type Result<T> = std::result::Result<T, Diagnostic>;

pub(crate) fn parse(source: &str, tokens: Vec<Token>) -> Result<UbnfFile> {
    let mut parser = Parser {
        source,
        tokens,
        pos: 0,
        depth: 0,
    };
    let mut grammars = Vec::new();
    while parser.current().kind != Kind::Eof {
        grammars.push(parser.grammar()?);
    }
    if grammars.is_empty() {
        return Err(parser.error("expected at least one grammar"));
    }
    Ok(UbnfFile {
        grammars,
        span: Span::default().through(parser.current().span),
    })
}

struct Parser<'a> {
    source: &'a str,
    tokens: Vec<Token>,
    pos: usize,
    depth: usize,
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
    fn integer(&mut self) -> Result<u32> {
        if let Kind::Number(value) = &self.current().kind {
            let number = value
                .parse::<i32>()
                .map_err(|_| self.invalid(self.current().span, "integer exceeds 2147483647"))?;
            self.pos += 1;
            Ok(number as u32)
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
            } else {
                SettingValue::String(self.dotted()?)
            };
            settings.push(GlobalSetting {
                key,
                value,
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
            tokens.push(self.token_decl()?);
        }
        let mut rules = Vec::new();
        while !self.is('}') && self.current().kind != Kind::Eof {
            rules.push(self.rule()?);
        }
        if rules.is_empty() {
            return Err(self.error("grammar requires at least one rule"));
        }
        self.expect('}')?;
        Ok(GrammarDecl {
            name,
            imports,
            settings,
            tokens,
            rules,
            span: self.span_from(start),
        })
    }
    fn token_decl(&mut self) -> Result<TokenDecl> {
        let start = self.current().span;
        self.word("token")?;
        let name = self.identifier()?;
        self.expect('=')?;
        let value_start = self.current().span;
        let value = self.dotted()?;
        let kind = match value.as_str() {
            "ANY" => TokenKind::Any,
            "EOF" => TokenKind::Eof,
            "EMPTY" => TokenKind::Empty,
            "UNTIL" | "NEGATION" | "LOOKAHEAD" | "NEGATIVE_LOOKAHEAD" | "CI" | "REGEX"
                if self.is('(') =>
            {
                self.expect('(')?;
                let arg = self.quoted()?;
                self.expect(')')?;
                match value.as_str() {
                    "UNTIL" => TokenKind::Until { terminator: arg },
                    "NEGATION" => TokenKind::Negation {
                        excluded_chars: arg,
                    },
                    "LOOKAHEAD" => TokenKind::Lookahead { pattern: arg },
                    "NEGATIVE_LOOKAHEAD" => TokenKind::NegativeLookahead { pattern: arg },
                    "CI" => TokenKind::CaseInsensitive { word: arg },
                    _ => TokenKind::Regex { pattern: arg },
                }
            }
            "CHAR_RANGE" if self.is('(') => {
                self.expect('(')?;
                let a = self.current().span;
                let min = self.quoted()?;
                self.expect(',')?;
                let b = self.current().span;
                let max = self.quoted()?;
                self.expect(')')?;
                let min = self.bmp(&min, a)?;
                let max = self.bmp(&max, b)?;
                if min > max {
                    return Err(self.invalid(value_start, "CHAR_RANGE minimum exceeds maximum"));
                }
                TokenKind::CharRange { min, max }
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
    fn rule(&mut self) -> Result<RuleDecl> {
        let start = self.current().span;
        let mut annotations = Vec::new();
        while self.is('@') {
            annotations.push(self.annotation()?);
        }
        let name = self.identifier()?;
        if self.current().kind != Kind::Define {
            return Err(self.error("expected ::="));
        }
        self.pos += 1;
        let body = self.body()?;
        self.expect(';')?;
        Ok(RuleDecl {
            annotations,
            name,
            body,
            span: self.span_from(start),
        })
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
        let name = self.identifier()?;
        let kind = match name.as_str() {
            "root" => AnnotationKind::Root,
            "leftAssoc" => AnnotationKind::LeftAssoc,
            "rightAssoc" => AnnotationKind::RightAssoc,
            "longestChoice" => AnnotationKind::LongestChoice,
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
                    let value = self.identifier()?;
                    self.expect(')')?;
                    Some(value)
                } else {
                    None
                };
                AnnotationKind::Whitespace { style }
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
                let level = self.integer()? as i32;
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
                            .map(str::trim)
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
            _ => {
                if self.is('(') {
                    return Err(diagnostic(
                        self.source,
                        self.span_from(start),
                        DiagnosticKind::UnsupportedSyntax,
                        format!("unknown parameterized annotation @{name}"),
                    ));
                }
                AnnotationKind::Simple { name }
            }
        };
        Ok(Spanned {
            kind,
            span: self.span_from(start),
        })
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
    fn element(&mut self) -> Result<AnnotatedElement> {
        let start = self.current().span;
        let typeof_constraint = if self.annotation_is("typeof") {
            self.pos += 2;
            self.expect('(')?;
            let name = self.identifier()?;
            self.expect(')')?;
            Some(name)
        } else {
            None
        };
        let mut element = self.atomic()?;
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
            let min = self.integer()?;
            let max = if self.eat(',') {
                if self.is('}') {
                    None
                } else {
                    Some(self.integer()?)
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
        } else if self.eat('%') {
            let separator = self.atomic()?;
            element = Spanned {
                kind: ElementKind::Separated {
                    element: Box::new(element),
                    separator: Box::new(separator),
                },
                span: self.span_from(element_start),
            };
        }
        // @typeof belongs to the next element, not to a capture named "typeof".
        let capture = if self.is('@')
            && !(self.annotation_is("typeof")
                && self
                    .tokens
                    .get(self.pos + 2)
                    .is_some_and(|t| t.kind == Kind::Symbol('(')))
        {
            self.pos += 1;
            Some(self.identifier()?)
        } else {
            None
        };
        Ok(AnnotatedElement {
            element,
            capture,
            typeof_constraint,
            span: self.span_from(start),
        })
    }
    fn atomic(&mut self) -> Result<AtomicElement> {
        let start = self.current().span;
        let kind = if self.eat('(') {
            let body = self.body()?;
            self.expect(')')?;
            ElementKind::Group(body)
        } else if self.eat('[') {
            let body = self.body()?;
            self.expect(']')?;
            ElementKind::Optional(body)
        } else if self.eat('{') {
            let body = self.body()?;
            self.expect('}')?;
            ElementKind::Repeat(body)
        } else if matches!(self.current().kind, Kind::Quoted(_)) {
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
