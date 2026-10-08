//! Browser lesson adapter: the real frontend/lowering/runtime, with an explicit subset.
//! Each browser request uses a fresh WASM instance; interned strings live for that instance.
use std::cell::RefCell;
use unlaxer_codegen::ir::{Cardinality, Expression, GrammarIr, Kind};
use unlaxer_runtime::{json_string as quote, Expr, Rule, Tree};

type Result<T> = std::result::Result<T, String>;
fn intern(text: &str) -> &'static str {
    Box::leak(text.to_owned().into_boxed_str())
}

fn lexical(
    e: &unlaxer_codegen::lexical::LexicalExpression,
) -> Result<unlaxer_runtime::lexical::LexicalExpression> {
    use unlaxer_codegen::lexical::Op as A;
    use unlaxer_runtime::lexical::Op as B;
    let op = match e.op {
        A::LITERAL => B::LITERAL,
        A::ANY => B::ANY,
        A::EOF => B::EOF,
        A::BOF => B::BOF,
        A::BOL => B::BOL,
        A::EOL => B::EOL,
        A::RANGE => B::RANGE,
        A::EXCEPT => B::EXCEPT,
        A::SEQUENCE => B::SEQUENCE,
        A::CHOICE => B::CHOICE,
        A::REPEAT => B::REPEAT,
        A::LOOK => B::LOOK,
        A::NOT => B::NOT,
        A::CAPTURE => B::CAPTURE,
        A::BACKREF => B::BACKREF,
        A::SCOPE => B::SCOPE,
        A::REF => return Err("unresolved lexical reference".into()),
    };
    Ok(unlaxer_runtime::lexical::LexicalExpression {
        op,
        text: intern(&e.text),
        min: e.min,
        max: e.max,
        children: e.children.iter().map(lexical).collect::<Result<_>>()?,
    })
}

fn expression(e: &Expression) -> Result<Expr> {
    use Expression::*;
    Ok(match e {
        Literal(text) => Expr::Literal(intern(text)),
        LexicalToken { name, expression: e } => Expr::Lexical(intern(name), lexical(e)?),
        Reference(id) => Expr::Rule(*id),
        Sequence(items) => Expr::Sequence(items.iter().map(expression).collect::<Result<_>>()?),
        Choice(items) => Expr::Choice(items.iter().map(expression).collect::<Result<_>>()?),
        Capture { name, expression: e } => expression(e)?.capture(intern(name)),
        OptionalExpr(child) => expression(child)?.optional_java(),
        Repeat { child, min, max } => expression(child)?.repeat_java(*min, *max),
        Delimited(child) => Expr::Sequence(vec![expression(child)?]),
        TriviaScope { child, java_whitespace } => expression(child)?.trivia_scope(*java_whitespace),
        TextValue(child) => expression(child)?.text_value(),
        ValueBoundary(child) => expression(child)?.value_boundary(),
        CharRangeToken { min, max } => Expr::CharRange(*min, *max),
        EofToken => Expr::Eof,
        // Reject instead of silently changing semantics outside the teaching subset.
        _ => return Err("学習エンジンの対応範囲外です。リテラル・宣言的 token・参照・選択・繰り返し・capture を使ってください。高度な機能は CLI / VS Code で試せます。".into()),
    })
}

pub struct LessonGrammar {
    ir: GrammarIr,
    rules: Vec<Rule>,
}
impl LessonGrammar {
    pub fn compile(source: &str) -> Result<Self> {
        if source.len() > 16384 {
            return Err("文法は 16 KiB 以下にしてください。".into());
        }
        let file = unlaxer_ubnf::parse(source).map_err(|e| e.to_string())?;
        if file.grammars.len() != 1 || !file.grammars[0].imports.is_empty() {
            return Err("学習画面では import のない 1 つの grammar を使います。".into());
        }
        if file.grammars[0]
            .tokens
            .iter()
            .any(|t| !matches!(t.kind, unlaxer_ubnf::ast::TokenKind::Declarative { .. }))
        {
            return Err("学習画面では宣言的 token を使います。".into());
        }
        let ir = unlaxer_generator::lowering::lower(&file.grammars[0])?;
        unlaxer_codegen::validate_ir(&ir).map_err(|e| e.to_string())?;
        if ir.rules.iter().any(|r| {
            r.mapping
                .as_ref()
                .is_some_and(|m| m.fields.iter().any(|f| f.kind == Kind::Value))
        }) {
            return Err("学習画面では text と node の混在 capture は使えません。個別の @mapping で包んでください。".into());
        }
        let rules = ir
            .rules
            .iter()
            .map(|r| {
                Ok(Rule {
                    name: intern(&r.name),
                    expression: expression(&r.body)?,
                })
            })
            .collect::<Result<_>>()?;
        Ok(Self { ir, rules })
    }

    fn project(&self, tree: &Tree, ids: &[usize]) -> Result<Vec<String>> {
        let mut out = Vec::new();
        for &id in ids {
            let node = &tree.nodes[id];
            let rule = self.ir.rules.get(node.rule);
            if rule.is_some_and(|r| r.skip) {
                continue;
            }
            let Some(mapping) = rule.and_then(|r| r.mapping.as_ref()) else {
                out.extend(self.project(tree, &node.children)?);
                continue;
            };
            let mut fields = Vec::new();
            for field in &mapping.fields {
                let mut values = Vec::new();
                for capture in node.captures.iter().filter(|c| c.name == field.name) {
                    if field.kind == Kind::Text {
                        values.push(quote(unlaxer_runtime::java_capture_text(
                            tree.text(capture.span),
                        )));
                    } else {
                        values.extend(self.project(tree, &capture.nodes)?);
                    }
                }
                let value = match field.cardinality {
                    Cardinality::Many => format!("[{}]", values.join(",")),
                    Cardinality::Optional if values.is_empty() => "null".into(),
                    _ if values.len() == 1 => values.remove(0),
                    _ => {
                        return Err(format!(
                            "{}: AST の値の数が合いません ({})",
                            field.name,
                            values.len()
                        ))
                    }
                };
                fields.push(format!("{}:{}", quote(&field.name), value));
            }
            out.push(format!(
                r#"{{"type":{},"span":[{},{}],"fields":{{{}}}}}"#,
                quote(&mapping.name),
                node.span.start,
                node.span.end,
                fields.join(",")
            ));
        }
        Ok(out)
    }

    pub fn analyze(&self, input: &str) -> String {
        if input.len() > 8192 {
            return r#"{"runtimeError":"試験入力は 8 KiB 以下にしてください。"}"#.into();
        }
        let mut context = unlaxer_runtime::ParseContext::new(input);
        let ok = context
            .parse_grammar(self.rules.clone(), self.ir.root, self.ir.java_whitespace)
            .is_ok();
        let prefix = format!(
            "[{ok},{},{}]",
            context.position(),
            context.matched_position()
        );
        match unlaxer_runtime::parse_detailed(
            &self.rules,
            self.ir.root,
            self.ir.java_whitespace,
            input,
        ) {
            Err(error) => format!(
                r#"{{"ok":false,"prefix":{prefix},"diagnostic":{}}}"#,
                error.canonical_json()
            ),
            Ok(tree) => {
                let ast = self.project(&tree, &[tree.root]).and_then(|mut values| {
                    if values.len() == 1 {
                        Ok(values.remove(0))
                    } else {
                        Err("入口に 1 つの mapped AST が必要です。".into())
                    }
                });
                match ast {
                    Ok(ast) => format!(r#"{{"ok":true,"prefix":{prefix},"ast":{ast}}}"#),
                    Err(error) => format!(
                        r#"{{"ok":true,"prefix":{prefix},"mappingError":{}}}"#,
                        quote(&error)
                    ),
                }
            }
        }
    }
}

#[derive(Default)]
struct State {
    input: Vec<u8>,
    output: Vec<u8>,
    grammar: Option<LessonGrammar>,
}
thread_local! { static STATE: RefCell<State> = RefCell::new(State::default()); }
#[no_mangle]
pub extern "C" fn pg_input(len: usize) -> usize {
    if len > 16384 {
        return 0;
    }
    STATE.with(|s| {
        let mut s = s.borrow_mut();
        s.input.resize(len, 0);
        s.input.as_mut_ptr() as usize
    })
}
#[no_mangle]
pub extern "C" fn pg_output() -> usize {
    STATE.with(|s| s.borrow().output.as_ptr() as usize)
}
#[no_mangle]
pub extern "C" fn pg_output_len() -> usize {
    STATE.with(|s| s.borrow().output.len())
}
fn execute(compile: bool) {
    STATE.with(|state| {
        let mut s = state.borrow_mut();
        let result = match String::from_utf8(s.input.clone()) {
            Err(_) => r#"{"runtimeError":"UTF-8 が必要です。"}"#.into(),
            Ok(input) if compile => {
                s.grammar = None;
                match LessonGrammar::compile(&input) {
                    Ok(grammar) => {
                        s.grammar = Some(grammar);
                        r#"{"compiled":true}"#.into()
                    }
                    Err(error) => format!(r#"{{"grammarError":{}}}"#, quote(&error)),
                }
            }
            Ok(input) => s.grammar.as_ref().map_or_else(
                || r#"{"runtimeError":"文法が未検証です。"}"#.into(),
                |g| g.analyze(&input),
            ),
        };
        s.output = if result.len() > 4 * 1024 * 1024 {
            r#"{"runtimeError":"結果が 4 MiB を超えました。"}"#.as_bytes().to_vec()
        } else {
            result.into_bytes()
        };
    });
}
#[no_mangle]
pub extern "C" fn pg_compile() {
    execute(true);
}
#[no_mangle]
pub extern "C" fn pg_parse() {
    execute(false);
}
