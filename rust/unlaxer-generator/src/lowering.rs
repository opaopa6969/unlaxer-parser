//! Supported structural semantics, independent of JVM parser class loading.
use std::cell::Cell;
use std::collections::{HashMap, HashSet};
use unlaxer_codegen::ir::*;
use unlaxer_ubnf::ast::{self, AnnotationKind, ElementKind, SettingValue, TokenKind};

type Result<T> = std::result::Result<T, String>;

fn whitespace_style(style: &str) -> Result<bool> {
    match style.trim() {
        value if value.eq_ignore_ascii_case("javaStyle") => Ok(true),
        value if value.eq_ignore_ascii_case("none") => Ok(false),
        _ => Err(format!("unsupported whitespace {style}")),
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Shape {
    kind: Kind,
    cardinality: Cardinality,
}

pub fn lower(grammar: &ast::GrammarDecl) -> Result<GrammarIr> {
    Lowering {
        grammar,
        ids: HashMap::new(),
        tokens: HashMap::new(),
        bodies: Vec::new(),
        mappings: Vec::new(),
        operators: Vec::new(),
        nullable: HashSet::new(),
        analysis_depth: Cell::new(0),
    }
    .run()
}

struct Lowering<'a> {
    grammar: &'a ast::GrammarDecl,
    ids: HashMap<String, usize>,
    tokens: HashMap<String, Expression>,
    bodies: Vec<Expression>,
    mappings: Vec<Option<(String, Vec<String>)>>,
    operators: Vec<Option<Operator>>,
    nullable: HashSet<usize>,
    analysis_depth: Cell<usize>,
}

struct AnalysisDepth<'a>(&'a Cell<usize>);
impl Drop for AnalysisDepth<'_> {
    fn drop(&mut self) {
        self.0.set(self.0.get() - 1);
    }
}

impl Lowering<'_> {
    fn enter_analysis(&self) -> Result<AnalysisDepth<'_>> {
        let depth = self.analysis_depth.get();
        if depth >= 256 {
            return Err("structural analysis depth exceeds 256".into());
        }
        self.analysis_depth.set(depth + 1);
        Ok(AnalysisDepth(&self.analysis_depth))
    }
    fn run(mut self) -> Result<GrammarIr> {
        if !self.grammar.imports.is_empty() {
            return Err("unsupported imports".into());
        }
        let mut whitespace = false;
        let mut settings = HashSet::new();
        for setting in &self.grammar.settings {
            if !settings.insert(&setting.key) {
                return Err(format!("duplicate setting {}", setting.key));
            }
            let SettingValue::String(value) = &setting.value else {
                return Err(format!("unsupported block setting {}", setting.key));
            };
            match setting.key.as_str() {
                "whitespace" => {
                    whitespace = whitespace_style(value)
                        .map_err(|_| format!("unsupported setting whitespace: {value}"))?
                }
                "package" => {}
                _ => return Err(format!("unsupported setting {}: {value}", setting.key)),
            }
        }
        for token in &self.grammar.tokens {
            let expression = token_expression(&token.kind)?;
            if self.tokens.insert(token.name.clone(), expression).is_some() {
                return Err(format!("duplicate token {}", token.name));
            }
        }
        let mut root = None;
        let has_scope = self.grammar.rules.iter().any(|rule| {
            rule.annotations
                .iter()
                .any(|annotation| matches!(annotation.kind, AnnotationKind::ScopeTree { .. }))
        });
        let mut rule_effects = Vec::new();
        let mut rule_whitespace = Vec::new();
        let mut has_local_trivia = false;
        let mut methods = HashMap::new();
        for (i, rule) in self.grammar.rules.iter().enumerate() {
            if self.ids.insert(rule.name.clone(), i).is_some()
                || self.tokens.contains_key(&rule.name)
            {
                return Err(format!("duplicate rule/token {}", rule.name));
            }
            let mut mapping = None;
            let mut associativity = None;
            let mut precedence = None;
            let mut local_whitespace = None;
            let mut interleave = false;
            let mut effects = RuleEffects::default();
            for annotation in &rule.annotations {
                match &annotation.kind {
                    AnnotationKind::Root => {
                        if root.replace(i).is_some() {
                            return Err("multiple @root annotations".into());
                        }
                    }
                    AnnotationKind::Mapping { class_name, params } => {
                        if mapping.is_some() {
                            return Err(format!("multiple @mapping on {}", rule.name));
                        }
                        identifier(class_name)?;
                        let method = method_name(class_name);
                        if let Some(previous) = methods.insert(method, class_name) {
                            if previous != class_name {
                                return Err(format!(
                                    "mapping method collision {previous} / {class_name}"
                                ));
                            }
                        }
                        mapping = Some((class_name.clone(), params.clone()));
                    }
                    AnnotationKind::LeftAssoc | AnnotationKind::RightAssoc => {
                        let value = if matches!(annotation.kind, AnnotationKind::LeftAssoc) {
                            Associativity::Left
                        } else {
                            Associativity::Right
                        };
                        if associativity.replace(value).is_some() {
                            return Err(format!(
                                "duplicate/conflicting associativity on {}",
                                rule.name
                            ));
                        }
                    }
                    AnnotationKind::Precedence { level } => {
                        if precedence.replace(*level).is_some() {
                            return Err(format!("duplicate @precedence on {}", rule.name));
                        }
                        if *level < 0 {
                            return Err("precedence must be non-negative".into());
                        }
                    }
                    AnnotationKind::Whitespace { style } => {
                        let enabled = whitespace_style(style.as_deref().unwrap_or("javaStyle"))?;
                        if local_whitespace.replace(enabled).is_some() {
                            return Err(format!("duplicate @whitespace on {}", rule.name));
                        }
                    }
                    AnnotationKind::Interleave { profile } => {
                        if interleave {
                            return Err(format!("duplicate @interleave on {}", rule.name));
                        }
                        if !matches!(profile.trim(), "javaStyle" | "commentsAndSpaces") {
                            return Err(format!("unsupported interleave profile {profile}"));
                        }
                        interleave = true;
                    }
                    AnnotationKind::ScopeTree { mode } => {
                        if effects.scope_mode.is_some() {
                            return Err(format!("duplicate @scopeTree on {}", rule.name));
                        }
                        effects.scope_mode = Some(match mode.trim() {
                            "lexical" => ScopeMode::Lexical,
                            "dynamic" => ScopeMode::Dynamic,
                            _ => return Err(format!("unsupported scopeTree mode {mode}")),
                        });
                    }
                    AnnotationKind::Declares {
                        symbol_capture,
                        description,
                    } => {
                        if effects.declares.is_some() {
                            return Err(format!("duplicate @declares on {}", rule.name));
                        }
                        effects.declares = Some(Declaration {
                            symbol_capture: symbol_capture.clone(),
                            description: description.clone(),
                        });
                    }
                    AnnotationKind::Backref { name } => {
                        if !has_scope {
                            return Err("@backref without @scopeTree is unsupported".into());
                        }
                        if effects.backref.replace(name.clone()).is_some() {
                            return Err(format!("duplicate @backref on {}", rule.name));
                        }
                    }
                    other => {
                        return Err(format!("unsupported annotation {other:?} on {}", rule.name))
                    }
                }
            }
            rule_effects.push(effects);
            has_local_trivia |= local_whitespace.is_some() || interleave;
            rule_whitespace.push(local_whitespace.unwrap_or(whitespace || interleave));
            if associativity.is_some() != precedence.is_some() {
                return Err(format!(
                    "associativity and @precedence must occur together on {}",
                    rule.name
                ));
            }
            self.operators.push(precedence.map(|precedence| Operator {
                associativity: associativity.expect("validated annotation pair"),
                precedence,
            }));
            self.mappings.push(mapping);
        }
        let root = root.ok_or("exactly one @root is required")?;
        self.check_parser_names()?;
        for rule in &self.grammar.rules {
            self.bodies.push(self.body(&rule.body)?);
        }
        loop {
            let mut changed = false;
            for (i, expression) in self.bodies.iter().enumerate() {
                if self.is_nullable(expression) {
                    changed |= self.nullable.insert(i);
                }
            }
            if !changed {
                break;
            }
        }
        for expression in &self.bodies {
            self.check_repetition(expression)?;
        }
        for i in 0..self.bodies.len() {
            self.check_left_recursion(i, &mut HashSet::new(), &mut HashSet::new())?;
        }
        if self.rule_shape(root, &mut HashSet::new())?
            != (Shape {
                kind: Kind::Node,
                cardinality: Cardinality::One,
            })
        {
            return Err("root must resolve to exactly one AST node".into());
        }
        let mut rules = Vec::new();
        for (i, expression) in self.bodies.iter().enumerate() {
            let captures = self.captures(expression)?;
            let effects = &rule_effects[i];
            for target in effects
                .declares
                .iter()
                .map(|decl| &decl.symbol_capture)
                .chain(effects.backref.iter())
            {
                if !captures.contains_key(target) {
                    return Err(format!(
                        "missing rule-effect capture {target} on {}",
                        self.grammar.rules[i].name
                    ));
                }
            }
            let mapping = if let Some((name, params)) = &self.mappings[i] {
                let names: HashSet<_> = params.iter().collect();
                if names.len() != params.len() || captures.keys().collect::<HashSet<_>>() != names {
                    return Err(format!(
                        "@mapping params must match capture names on {}",
                        self.grammar.rules[i].name
                    ));
                }
                let mut fields = Vec::new();
                for name in params {
                    identifier(name)?;
                    if name == "span" || name == "semantics" {
                        return Err(format!("reserved capture name {name}"));
                    }
                    let shape = captures[name];
                    fields.push(Field {
                        name: name.clone(),
                        kind: shape.kind,
                        cardinality: shape.cardinality,
                    });
                }
                let mapping = Mapping {
                    name: name.clone(),
                    fields,
                };
                Some(mapping)
            } else {
                if !captures.is_empty() && *effects == RuleEffects::default() {
                    return Err(format!(
                        "captures without @mapping on {}",
                        self.grammar.rules[i].name
                    ));
                }
                None
            };
            if let Some(operator) = self.operators[i] {
                match operator.associativity {
                    Associativity::Left => self.check_assoc(i, mapping.as_ref())?,
                    Associativity::Right => {
                        self.lower_right_assoc(i, mapping.as_ref())?;
                    }
                    Associativity::None => unreachable!("validated annotation pair"),
                }
                let mut refs = HashSet::new();
                references(expression, &mut refs);
                for reference in refs {
                    if reference == i {
                        continue;
                    }
                    if let Some(operand) = self.operators[reference] {
                        if operand.precedence <= operator.precedence {
                            return Err(format!(
                                "precedence order: {} must be lower than {}",
                                self.grammar.rules[i].name, self.grammar.rules[reference].name
                            ));
                        }
                    }
                }
            }
            rules.push(Rule {
                name: self.grammar.rules[i].name.clone(),
                body: expression.clone(),
                mapping,
                operator: self.operators[i],
            });
        }
        // A shared variant has one public type contract, independent of declaration order.
        let mut variants: HashMap<String, Mapping> = HashMap::new();
        for mapping in rules.iter().filter_map(|rule| rule.mapping.as_ref()) {
            if let Some(previous) = variants.get_mut(&mapping.name) {
                if previous.fields.len() != mapping.fields.len()
                    || previous
                        .fields
                        .iter()
                        .zip(&mapping.fields)
                        .any(|(a, b)| a.name != b.name || a.cardinality != b.cardinality)
                {
                    return Err(format!(
                        "incompatible shared mapping schema {}",
                        mapping.name
                    ));
                }
                for (field, other) in previous.fields.iter_mut().zip(&mapping.fields) {
                    field.kind = join_kind(field.kind, other.kind);
                }
            } else {
                variants.insert(mapping.name.clone(), mapping.clone());
            }
        }
        let needs_values = variants
            .values()
            .flat_map(|m| &m.fields)
            .any(|f| f.kind == Kind::Value);
        for (i, rule) in rules.iter_mut().enumerate() {
            if let Some(mapping) = &rule.mapping {
                rule.mapping = Some(variants[&mapping.name].clone());
            }
            if needs_values {
                rule.body = if rule.mapping.is_none() {
                    self.project_helper_value(&rule.body)?
                } else {
                    self.project_text_values(&rule.body, rule.mapping.as_ref())?
                };
            }
            if rule
                .operator
                .is_some_and(|op| op.associativity == Associativity::Right)
            {
                // Shape validation happened before projection. Only the outer structure changes.
                rule.body = right_associative_body(&rule.body);
            }
            // Resolve unannotated callees against the grammar, not a caller's local mode.
            if has_local_trivia {
                rule.body = Expression::TriviaScope {
                    child: Box::new(rule.body.clone()),
                    java_whitespace: rule_whitespace[i],
                };
            }
            if rule_effects[i] != RuleEffects::default() {
                rule.body = Expression::RuleEffects {
                    child: Box::new(rule.body.clone()),
                    effects: rule_effects[i].clone(),
                };
            }
        }
        Ok(GrammarIr {
            rules,
            root,
            java_whitespace: whitespace,
        })
    }

    fn check_parser_names(&self) -> Result<()> {
        // Match the shared frontend's Java-name collision guard, including short token bindings.
        let rule_classes: HashSet<_> = self
            .grammar
            .rules
            .iter()
            .map(|rule| format!("{}Parser", rule.name))
            .collect();
        for token in &self.grammar.tokens {
            let class = match &token.kind {
                TokenKind::Simple { parser_class } if !parser_class.contains('.') => {
                    Some(parser_class.clone())
                }
                TokenKind::Simple { .. }
                | TokenKind::Until { .. }
                | TokenKind::Negation { .. }
                | TokenKind::CharRange { .. } => Some(parser_class_name(&token.name)),
                _ => None,
            };
            if class
                .as_ref()
                .is_some_and(|name| rule_classes.contains(name))
            {
                return Err(format!(
                    "rule/token parser name collision for {}",
                    token.name
                ));
            }
        }
        Ok(())
    }

    fn body(&self, body: &ast::RuleBody) -> Result<Expression> {
        if body.alternatives.is_empty() {
            return Err("empty choice".into());
        }
        let mut alternatives = Vec::new();
        for sequence in &body.alternatives {
            if sequence.elements.is_empty() {
                return Err("empty sequence".into());
            }
            let mut elements = Vec::new();
            for element in &sequence.elements {
                if element.typeof_constraint.is_some() {
                    return Err("unsupported @typeof".into());
                }
                let value = self.atomic(&element.element)?;
                elements.push(if let Some(name) = &element.capture {
                    capture(name, value)?
                } else {
                    value
                });
            }
            alternatives.push(if body.alternatives.len() > 1 && elements.len() == 1 {
                elements.remove(0)
            } else {
                Expression::Sequence(elements)
            });
        }
        Ok(if alternatives.len() == 1 {
            alternatives.remove(0)
        } else {
            Expression::Choice(alternatives)
        })
    }

    fn atomic(&self, atom: &ast::AtomicElement) -> Result<Expression> {
        Ok(match &atom.kind {
            ElementKind::Terminal(text) => {
                if text.is_empty() {
                    return Err("empty literal".into());
                }
                Expression::Literal(text.clone())
            }
            ElementKind::RuleRef { namespace, name } => {
                if namespace.is_some() {
                    return Err("unsupported qualified rule reference".into());
                }
                if let Some(token) = self.tokens.get(name) {
                    token.clone()
                } else {
                    Expression::Reference(
                        *self
                            .ids
                            .get(name)
                            .ok_or_else(|| format!("unknown reference {name}"))?,
                    )
                }
            }
            ElementKind::Group(body) => self.body(body)?,
            ElementKind::Optional(body) => {
                Expression::OptionalExpr(Box::new(self.single_body(body, false)?))
            }
            ElementKind::Repeat(body) => Expression::Repeat {
                child: Box::new(self.single_body(body, true)?),
                min: 0,
                max: None,
            },
            ElementKind::OneOrMore(child) => Expression::Repeat {
                child: Box::new(self.repeated_atom(child)?),
                min: 1,
                max: None,
            },
            ElementKind::BoundedRepeat { element, min, max } => {
                if max.is_some_and(|max| max < *min) {
                    return Err("invalid repetition bounds".into());
                }
                Expression::Repeat {
                    child: Box::new(self.repeated_atom(element)?),
                    min: *min as usize,
                    max: max
                        .filter(|value| *value != i32::MAX as u32)
                        .map(|value| value as usize),
                }
            }
            ElementKind::Separated { element, separator } => Expression::Separated {
                child: Box::new(self.atomic(element)?),
                separator: Box::new(self.atomic(separator)?),
            },
            ElementKind::Error(_) => return Err("unsupported error element".into()),
        })
    }

    fn single_body(&self, body: &ast::RuleBody, only_reference: bool) -> Result<Expression> {
        let lowered = self.body(body)?;
        if let Expression::Sequence(elements) = &lowered {
            if elements.len() == 1 {
                let child = &elements[0];
                let bare = if let Expression::Capture { expression, .. } = child {
                    expression.as_ref()
                } else {
                    child
                };
                if matches!(
                    body.alternatives[0].elements[0].element.kind,
                    ElementKind::RuleRef { .. }
                ) || (!only_reference && matches!(bare, Expression::Literal(_)))
                {
                    return Ok(child.clone());
                }
            }
        }
        Ok(lowered)
    }

    fn repeated_atom(&self, atom: &ast::AtomicElement) -> Result<Expression> {
        let child = self.atomic(atom)?;
        Ok(
            if matches!(child, Expression::Choice(_) | Expression::Literal(_)) {
                Expression::Delimited(Box::new(child))
            } else if matches!(atom.kind, ElementKind::RuleRef { .. })
                || matches!(child, Expression::Sequence(_))
            {
                child
            } else {
                Expression::Sequence(vec![child])
            },
        )
    }

    fn is_nullable(&self, expression: &Expression) -> bool {
        match expression {
            Expression::EmptyToken
            | Expression::EofToken
            | Expression::LookaheadToken { .. }
            | Expression::UntilToken(_)
            | Expression::OptionalExpr(_) => true,
            Expression::Reference(rule) => self.nullable.contains(rule),
            Expression::Capture { expression, .. } | Expression::Delimited(expression) => {
                self.is_nullable(expression)
            }
            Expression::Sequence(elements) => elements.iter().all(|e| self.is_nullable(e)),
            Expression::Choice(elements) => elements.iter().any(|e| self.is_nullable(e)),
            Expression::Repeat { child, min, .. } => *min == 0 || self.is_nullable(child),
            Expression::Separated { child, .. } => self.is_nullable(child),
            _ => false,
        }
    }

    fn check_repetition(&self, expression: &Expression) -> Result<()> {
        if let Expression::Repeat {
            child, max: None, ..
        } = expression
        {
            if self.is_nullable(child) {
                return Err("nullable unbounded repetition".into());
            }
        }
        if let Expression::Separated { child, separator } = expression {
            if self.shape(separator, &mut HashSet::new())?.kind != Kind::Text {
                return Err("mapped separator".into());
            }
            if self.is_nullable(child) && self.is_nullable(separator) {
                return Err("nullable unbounded separation".into());
            }
        }
        for child in children(expression) {
            self.check_repetition(child)?;
        }
        Ok(())
    }

    fn check_left_recursion(
        &self,
        rule: usize,
        visiting: &mut HashSet<usize>,
        done: &mut HashSet<usize>,
    ) -> Result<()> {
        if done.contains(&rule) {
            return Ok(());
        }
        if !visiting.insert(rule) {
            return Err(format!(
                "left recursion at {}",
                self.grammar.rules[rule].name
            ));
        }
        if visiting.len() > 256 {
            return Err("rule analysis depth exceeds 256".into());
        }
        let mut leading = HashSet::new();
        self.leading_rules(&self.bodies[rule], &mut leading);
        for child in leading {
            self.check_left_recursion(child, visiting, done)?;
        }
        visiting.remove(&rule);
        done.insert(rule);
        Ok(())
    }

    fn leading_rules(&self, expression: &Expression, result: &mut HashSet<usize>) {
        match expression {
            Expression::Reference(rule) => {
                result.insert(*rule);
            }
            Expression::Sequence(elements) => {
                for element in elements {
                    self.leading_rules(element, result);
                    if !self.is_nullable(element) {
                        break;
                    }
                }
            }
            Expression::Separated { child, separator } => {
                self.leading_rules(child, result);
                if self.is_nullable(child) {
                    self.leading_rules(separator, result);
                }
            }
            _ => {
                for child in children(expression) {
                    self.leading_rules(child, result);
                }
            }
        }
    }

    fn rule_shape(&self, rule: usize, visiting: &mut HashSet<usize>) -> Result<Shape> {
        let _depth = self.enter_analysis()?;
        if self.mappings[rule].is_some() {
            return Ok(Shape {
                kind: Kind::Node,
                cardinality: Cardinality::One,
            });
        }
        if !visiting.insert(rule) {
            return Err(format!(
                "recursive unmapped rule {}",
                self.grammar.rules[rule].name
            ));
        }
        if visiting.len() > 256 {
            return Err("rule shape depth exceeds 256".into());
        }
        let shape = self.shape(&self.bodies[rule], visiting)?;
        visiting.remove(&rule);
        Ok(shape)
    }

    fn shape(&self, expression: &Expression, visiting: &mut HashSet<usize>) -> Result<Shape> {
        let _depth = self.enter_analysis()?;
        match expression {
            Expression::Reference(rule) => self.rule_shape(*rule, visiting),
            Expression::Capture { expression, .. } | Expression::Delimited(expression) => {
                self.shape(expression, visiting)
            }
            Expression::OptionalExpr(child) => Ok(wrap_node(
                self.shape(child, visiting)?,
                Cardinality::Optional,
            )),
            Expression::Repeat { child, .. } => {
                Ok(wrap_node(self.shape(child, visiting)?, Cardinality::Many))
            }
            Expression::Separated { child, separator } => {
                if self.shape(separator, visiting)?.kind != Kind::Text {
                    return Err("mapped separator".into());
                }
                Ok(wrap_node(self.shape(child, visiting)?, Cardinality::Many))
            }
            Expression::Sequence(elements) => {
                let mut nodes = Vec::new();
                for element in elements {
                    let shape = self.shape(element, visiting)?;
                    if shape.kind != Kind::Text {
                        nodes.push(shape);
                    }
                }
                Ok(match nodes.len() {
                    0 => text_shape(),
                    1 => nodes[0],
                    _ => Shape {
                        kind: nodes
                            .iter()
                            .map(|s| s.kind)
                            .reduce(join_kind)
                            .expect("nonempty nodes"),
                        cardinality: Cardinality::Many,
                    },
                })
            }
            Expression::Choice(elements) => {
                let mut result = self.shape(&elements[0], visiting)?;
                for element in &elements[1..] {
                    result = merge(result, self.shape(element, visiting)?, false)?;
                }
                Ok(result)
            }
            _ => Ok(text_shape()),
        }
    }

    /// Preserve a helper's scalar semantic value, but never collapse its collection.
    /// Container children use the same boundary so inline repeated groups retain
    /// their own delimiters even when an outer capture collects many values.
    fn project_helper_value(&self, expression: &Expression) -> Result<Expression> {
        let projected = self.project_text_values(expression, None)?;
        let shape = self.shape(expression, &mut HashSet::new())?;
        Ok(
            if shape.kind == Kind::Value && shape.cardinality != Cardinality::Many {
                Expression::ValueBoundary(Box::new(projected))
            } else {
                projected
            },
        )
    }

    /// Preserve each lexical alternative as a CST node before a value mapper visits it.
    /// The original bodies remain untouched for shape analysis (including recursive references).
    fn project_text_values(
        &self,
        expression: &Expression,
        mapping: Option<&Mapping>,
    ) -> Result<Expression> {
        let _depth = self.enter_analysis()?;
        Ok(match expression {
            Expression::Choice(alternatives) => {
                let mixed = self.shape(expression, &mut HashSet::new())?.kind == Kind::Value;
                Expression::Choice(
                    alternatives
                        .iter()
                        .map(|alternative| {
                            let projected = self.project_text_values(alternative, mapping)?;
                            Ok(
                                if mixed
                                    && self.shape(alternative, &mut HashSet::new())?.kind
                                        == Kind::Text
                                {
                                    Expression::TextValue(Box::new(projected))
                                } else {
                                    projected
                                },
                            )
                        })
                        .collect::<Result<_>>()?,
                )
            }
            Expression::Capture {
                name,
                expression: child,
            } => {
                let projected = self.project_text_values(child, mapping)?;
                let is_value = mapping.is_some_and(|m| {
                    m.fields
                        .iter()
                        .any(|f| f.name == *name && f.kind == Kind::Value)
                });
                let shape = self.shape(child, &mut HashSet::new())?;
                let projected = if is_value && shape.kind == Kind::Text {
                    Expression::TextValue(Box::new(projected))
                } else if is_value
                    && shape.kind == Kind::Value
                    && shape.cardinality != Cardinality::Many
                {
                    Expression::ValueBoundary(Box::new(projected))
                } else {
                    projected
                };
                Expression::Capture {
                    name: name.clone(),
                    expression: Box::new(projected),
                }
            }
            Expression::Sequence(elements) => Expression::Sequence(
                elements
                    .iter()
                    .map(|element| self.project_text_values(element, mapping))
                    .collect::<Result<_>>()?,
            ),
            Expression::OptionalExpr(child) => {
                Expression::OptionalExpr(Box::new(if mapping.is_none() {
                    self.project_helper_value(child)?
                } else {
                    self.project_text_values(child, mapping)?
                }))
            }
            Expression::Repeat { child, min, max } => Expression::Repeat {
                child: Box::new(if mapping.is_none() {
                    self.project_helper_value(child)?
                } else {
                    self.project_text_values(child, mapping)?
                }),
                min: *min,
                max: *max,
            },
            Expression::Separated { child, separator } => Expression::Separated {
                child: Box::new(if mapping.is_none() {
                    self.project_helper_value(child)?
                } else {
                    self.project_text_values(child, mapping)?
                }),
                separator: Box::new(self.project_text_values(separator, mapping)?),
            },
            Expression::Delimited(child) => {
                Expression::Delimited(Box::new(self.project_text_values(child, mapping)?))
            }
            _ => expression.clone(),
        })
    }

    fn captures(&self, expression: &Expression) -> Result<HashMap<String, Shape>> {
        let _depth = self.enter_analysis()?;
        Ok(match expression {
            Expression::Capture { name, expression } => {
                let mut result = self.captures(expression)?;
                insert_capture(
                    &mut result,
                    name,
                    self.shape(expression, &mut HashSet::new())?,
                    true,
                )?;
                result
            }
            Expression::OptionalExpr(child) => {
                self.wrapped_captures(child, Cardinality::Optional)?
            }
            Expression::Delimited(child) => self.captures(child)?,
            Expression::Repeat { child, .. } => self.wrapped_captures(child, Cardinality::Many)?,
            Expression::Separated { child, separator } => {
                if !self.captures(separator)?.is_empty() {
                    return Err("captures in separator".into());
                }
                self.wrapped_captures(child, Cardinality::Many)?
            }
            Expression::Sequence(elements) => {
                let mut result = HashMap::new();
                for element in elements {
                    for (name, shape) in self.captures(element)? {
                        insert_capture(&mut result, &name, shape, true)?;
                    }
                }
                result
            }
            Expression::Choice(elements) => {
                let alternatives = elements
                    .iter()
                    .map(|e| self.captures(e))
                    .collect::<Result<Vec<_>>>()?;
                let mut result = HashMap::new();
                for alternative in &alternatives {
                    for (name, shape) in alternative {
                        insert_capture(&mut result, name, *shape, false)?;
                    }
                }
                for (name, shape) in &mut result {
                    if alternatives
                        .iter()
                        .any(|alternative| !alternative.contains_key(name))
                    {
                        *shape = wrap(*shape, Cardinality::Optional);
                    }
                }
                result
            }
            _ => HashMap::new(),
        })
    }

    fn wrapped_captures(
        &self,
        child: &Expression,
        cardinality: Cardinality,
    ) -> Result<HashMap<String, Shape>> {
        Ok(self
            .captures(child)?
            .into_iter()
            .map(|(name, shape)| (name, wrap(shape, cardinality)))
            .collect())
    }

    /// Keep the original vector schema; every CST node has zero or one recursive tail.
    fn lower_right_assoc(&self, rule: usize, mapping: Option<&Mapping>) -> Result<Expression> {
        let error = || {
            format!("@rightAssoc requires left {{ op Self }} with scalar base and params=[left, op, right] on {}", self.grammar.rules[rule].name)
        };
        // Normalization erases the difference between { tail } and (tail){0,}.
        // Java's right-associative parser rewrite accepts only the former syntax.
        let [declared] = self.grammar.rules[rule].body.alternatives.as_slice() else {
            return Err(error());
        };
        if declared.elements.len() != 2
            || !matches!(declared.elements[1].element.kind, ElementKind::Repeat(_))
        {
            return Err(error());
        }
        let Some(mapping) = mapping else {
            return Err(error());
        };
        if mapping
            .fields
            .iter()
            .map(|f| f.name.as_str())
            .collect::<Vec<_>>()
            != ["left", "op", "right"]
        {
            return Err(error());
        }
        let Expression::Sequence(elements) = &self.bodies[rule] else {
            return Err(error());
        };
        let [left @ Expression::Capture {
            name: left_name,
            expression: base,
        }, Expression::Repeat {
            child,
            min: 0,
            max: None,
        }] = elements.as_slice()
        else {
            return Err(error());
        };
        let Expression::Sequence(tail) = child.as_ref() else {
            return Err(error());
        };
        let [op @ Expression::Capture {
            name: op_name,
            expression: operator,
        }, right @ Expression::Capture {
            name: right_name,
            expression: recursive,
        }] = tail.as_slice()
        else {
            return Err(error());
        };
        if left_name != "left"
            || op_name != "op"
            || right_name != "right"
            || recursive.as_ref() != &Expression::Reference(rule)
            || !self.captures(base)?.is_empty()
            || !self.captures(operator)?.is_empty()
            || mapping.fields[0].cardinality != Cardinality::One
            || mapping.fields[1]
                != (Field {
                    name: "op".into(),
                    kind: Kind::Text,
                    cardinality: Cardinality::Many,
                })
            || mapping.fields[2]
                != (Field {
                    name: "right".into(),
                    kind: Kind::Node,
                    cardinality: Cardinality::Many,
                })
        {
            return Err(error());
        }
        Ok(Expression::Choice(vec![
            Expression::Sequence(vec![left.clone(), op.clone(), right.clone()]),
            left.clone(),
        ]))
    }

    fn check_assoc(&self, rule: usize, mapping: Option<&Mapping>) -> Result<()> {
        let error = || {
            format!("@leftAssoc requires left {{ op right }} with scalar operands and params=[left, op, right] on {}", self.grammar.rules[rule].name)
        };
        let Some(mapping) = mapping else {
            return Err(error());
        };
        if mapping
            .fields
            .iter()
            .map(|f| f.name.as_str())
            .collect::<Vec<_>>()
            != ["left", "op", "right"]
        {
            return Err(error());
        }
        let Expression::Sequence(elements) = &self.bodies[rule] else {
            return Err(error());
        };
        let [Expression::Capture {
            name: left_name,
            expression: left,
        }, Expression::Repeat {
            child,
            min: 0,
            max: None,
        }] = elements.as_slice()
        else {
            return Err(error());
        };
        let Expression::Sequence(tail) = child.as_ref() else {
            return Err(error());
        };
        let [Expression::Capture {
            name: op_name,
            expression: op,
        }, Expression::Capture {
            name: right_name,
            expression: right,
        }] = tail.as_slice()
        else {
            return Err(error());
        };
        if left_name != "left"
            || op_name != "op"
            || right_name != "right"
            || !self.captures(left)?.is_empty()
            || !self.captures(op)?.is_empty()
            || !self.captures(right)?.is_empty()
            || mapping.fields[0].cardinality != Cardinality::One
            || mapping.fields[1]
                != (Field {
                    name: "op".into(),
                    kind: Kind::Text,
                    cardinality: Cardinality::Many,
                })
            || mapping.fields[2].cardinality != Cardinality::Many
            || mapping.fields[0].kind != mapping.fields[2].kind
            || self.shape(right, &mut HashSet::new())?.cardinality != Cardinality::One
        {
            return Err(error());
        }
        Ok(())
    }
}

fn identifier(value: &str) -> Result<()> {
    if !value
        .as_bytes()
        .first()
        .is_some_and(u8::is_ascii_alphabetic)
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_')
        || ["Self", "self", "super", "crate"].contains(&value)
    {
        return Err(format!("unsupported identifier {value}"));
    }
    Ok(())
}

fn method_name(value: &str) -> String {
    let mut method = String::from("eval_");
    let mut previous = None;
    for ch in value.chars() {
        if ch.is_ascii_uppercase()
            && previous.is_some_and(|c: char| c.is_ascii_lowercase() || c.is_ascii_digit())
        {
            method.push('_');
        }
        method.push(ch.to_ascii_lowercase());
        previous = Some(ch);
    }
    method
}

fn parser_class_name(name: &str) -> String {
    let mut result = String::new();
    for part in name.split('_').filter(|s| !s.is_empty()) {
        let mut chars = part.chars();
        if let Some(first) = chars.next() {
            result.extend(first.to_uppercase());
            result.push_str(&chars.as_str().to_lowercase());
        }
    }
    result.push_str("Parser");
    result
}

fn token_expression(token: &TokenKind) -> Result<Expression> {
    Ok(match token {
        TokenKind::Simple { parser_class } => match parser_class.as_str() {
            "NumberParser" | "org.unlaxer.parser.elementary.NumberParser" => {
                Expression::NumberToken
            }
            "IdentifierParser" | "org.unlaxer.parser.clang.IdentifierParser" => {
                Expression::IdentifierToken
            }
            "SingleQuotedParser" | "org.unlaxer.parser.elementary.SingleQuotedParser" => {
                Expression::QuotedToken('\'')
            }
            "DoubleQuotedParser" | "org.unlaxer.parser.elementary.DoubleQuotedParser" => {
                Expression::QuotedToken('"')
            }
            "org.unlaxer.tinyexpression.parser.StringLiteralParser" => Expression::Choice(vec![
                Expression::QuotedToken('"'),
                Expression::QuotedToken('\''),
            ]),
            "org.unlaxer.tinyexpression.parser.javalang.CodeStartParser" => {
                Expression::CodeStartToken
            }
            "org.unlaxer.tinyexpression.parser.javalang.CodeEndParser" => Expression::CodeEndToken,
            "EndOfSourceParser" | "org.unlaxer.parser.elementary.EndOfSourceParser" => {
                Expression::EofToken
            }
            other => return Err(format!("unsupported external token {other}")),
        },
        TokenKind::Any => Expression::AnyToken,
        TokenKind::Eof => Expression::EofToken,
        TokenKind::Empty => Expression::EmptyToken,
        TokenKind::CharRange { min, max } => {
            if min > max || *max as u32 > 0xffff {
                return Err("invalid BMP character range".into());
            }
            Expression::CharRangeToken {
                min: *min,
                max: *max,
            }
        }
        TokenKind::Negation { excluded_chars } => Expression::ExceptToken(excluded_chars.clone()),
        TokenKind::Until { terminator } => Expression::UntilToken(terminator.clone()),
        TokenKind::Lookahead { pattern } => Expression::LookaheadToken {
            pattern: pattern.clone(),
            positive: true,
        },
        TokenKind::NegativeLookahead { pattern } => Expression::LookaheadToken {
            pattern: pattern.clone(),
            positive: false,
        },
        other => return Err(format!("unsupported token {other:?}")),
    })
}

fn capture(name: &str, expression: Expression) -> Result<Expression> {
    if let Expression::Sequence(elements) = &expression {
        if elements.len() == 1 {
            let mut inner = &elements[0];
            while let Expression::Sequence(nested) = inner {
                if nested.len() != 1 {
                    break;
                }
                inner = &nested[0];
            }
            if matches!(
                inner,
                Expression::OptionalExpr(_)
                    | Expression::Repeat { .. }
                    | Expression::Separated { .. }
            ) {
                return Err(
                    "nested container capture; name the inner element or a mapped wrapper rule"
                        .into(),
                );
            }
        }
    }
    Ok(match expression {
        Expression::Delimited(child) => Expression::Delimited(Box::new(capture(name, *child)?)),
        Expression::OptionalExpr(child) => {
            Expression::OptionalExpr(Box::new(capture(name, *child)?))
        }
        Expression::Repeat { child, min, max } => Expression::Repeat {
            child: Box::new(capture(name, *child)?),
            min,
            max,
        },
        Expression::Separated { child, separator } => Expression::Separated {
            child: Box::new(capture(name, *child)?),
            separator,
        },
        expression => Expression::Capture {
            name: name.into(),
            expression: Box::new(expression),
        },
    })
}

fn children(expression: &Expression) -> Vec<&Expression> {
    match expression {
        Expression::Sequence(elements) | Expression::Choice(elements) => elements.iter().collect(),
        Expression::Capture { expression, .. }
        | Expression::Delimited(expression)
        | Expression::OptionalExpr(expression)
        | Expression::Repeat {
            child: expression, ..
        } => vec![expression],
        Expression::Separated { child, separator } => vec![child, separator],
        _ => vec![],
    }
}

fn references(expression: &Expression, result: &mut HashSet<usize>) {
    if let Expression::Reference(rule) = expression {
        result.insert(*rule);
    }
    for child in children(expression) {
        references(child, result);
    }
}

fn right_associative_body(body: &Expression) -> Expression {
    let Expression::Sequence(elements) = body else {
        unreachable!("validated right-associative sequence");
    };
    let Expression::Repeat { child, .. } = &elements[1] else {
        unreachable!("validated right-associative repeat");
    };
    let Expression::Sequence(tail) = child.as_ref() else {
        unreachable!("validated right-associative tail");
    };
    Expression::Choice(vec![
        Expression::Sequence(vec![elements[0].clone(), tail[0].clone(), tail[1].clone()]),
        elements[0].clone(),
    ])
}

fn join_kind(left: Kind, right: Kind) -> Kind {
    if left == right {
        left
    } else {
        Kind::Value
    }
}

fn text_shape() -> Shape {
    Shape {
        kind: Kind::Text,
        cardinality: Cardinality::One,
    }
}
fn wrap_node(shape: Shape, cardinality: Cardinality) -> Shape {
    if shape.kind == Kind::Text {
        shape
    } else {
        wrap(shape, cardinality)
    }
}
fn wrap(shape: Shape, cardinality: Cardinality) -> Shape {
    Shape {
        kind: shape.kind,
        cardinality: if shape.cardinality == Cardinality::Many || cardinality == Cardinality::Many {
            Cardinality::Many
        } else if cardinality == Cardinality::Optional {
            Cardinality::Optional
        } else {
            shape.cardinality
        },
    }
}
fn merge(left: Shape, right: Shape, sequence: bool) -> Result<Shape> {
    Ok(Shape {
        kind: join_kind(left.kind, right.kind),
        cardinality: if sequence
            || left.cardinality == Cardinality::Many
            || right.cardinality == Cardinality::Many
        {
            Cardinality::Many
        } else if left.cardinality == Cardinality::Optional
            || right.cardinality == Cardinality::Optional
        {
            Cardinality::Optional
        } else {
            Cardinality::One
        },
    })
}
fn insert_capture(
    result: &mut HashMap<String, Shape>,
    name: &str,
    shape: Shape,
    sequence: bool,
) -> Result<()> {
    let shape = if let Some(previous) = result.get(name) {
        merge(*previous, shape, sequence)?
    } else {
        shape
    };
    result.insert(name.into(), shape);
    Ok(())
}
