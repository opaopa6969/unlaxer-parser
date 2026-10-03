//! Resolve declarative tokens without executing host parser code.
use std::collections::{HashMap, HashSet};
use unlaxer_codegen::lexical::{LexicalExpression as Program, Op as ProgramOp};
use unlaxer_ubnf::lexical::{LexicalExpression as Syntax, Op};
use unlaxer_ubnf::{GrammarDecl, SettingValue, TokenKind};

pub fn compile(grammar: &GrammarDecl) -> Result<HashMap<String, Program>, String> {
    Ok(compile_syntax(grammar)?
        .into_iter()
        .map(|(name, value)| (name, convert(&value)))
        .collect())
}

pub(crate) fn compile_syntax(grammar: &GrammarDecl) -> Result<HashMap<String, Syntax>, String> {
    let mut definitions = HashMap::new();
    for token in &grammar.tokens {
        if let TokenKind::Declarative { expression } = &token.kind {
            if definitions.insert(token.name.clone(), expression).is_some() {
                return Err(format!("duplicate token {}", token.name));
            }
        }
    }
    if !definitions.is_empty()
        && !grammar
            .settings
            .iter()
            .any(|s| s.key == "ubnf" && matches!(&s.value, SettingValue::String(v) if v == "v2"))
    {
        return Err("declarative tokens require @ubnf: v2".into());
    }
    let mut compiled = HashMap::new();
    for token in &grammar.tokens {
        let name = &token.name;
        if !definitions.contains_key(name) {
            continue;
        }
        let expression = expand(
            &Syntax::leaf(Op::REF, name.clone()),
            &definitions,
            &mut HashSet::new(),
            0,
            &mut 0,
        )?;
        validate(&expression, &mut HashSet::new())?;
        compiled.insert(name.clone(), expression);
    }
    Ok(compiled)
}
fn expand(
    e: &Syntax,
    definitions: &HashMap<String, &Syntax>,
    visiting: &mut HashSet<String>,
    depth: usize,
    nodes: &mut usize,
) -> Result<Syntax, String> {
    if depth > 128 {
        return Err("lexical expansion exceeds 128".into());
    }
    *nodes += 1;
    if *nodes > 4096 {
        return Err("lexical expansion exceeds 4096 nodes".into());
    }
    if e.op == Op::REF {
        let target = definitions
            .get(&e.text)
            .ok_or_else(|| format!("undefined declarative token {}", e.text))?;
        if !visiting.insert(e.text.clone()) {
            return Err(format!("cyclic lexical reference {}", e.text));
        }
        let result = Syntax::node(
            Op::SCOPE,
            vec![expand(target, definitions, visiting, depth + 1, nodes)?],
        );
        visiting.remove(&e.text);
        return Ok(result);
    }
    Ok(Syntax {
        children: e
            .children
            .iter()
            .map(|c| expand(c, definitions, visiting, depth + 1, nodes))
            .collect::<Result<_, _>>()?,
        ..e.clone()
    })
}
fn validate(e: &Syntax, bound: &mut HashSet<String>) -> Result<(), String> {
    match e.op {
        Op::BACKREF => {
            if !bound.contains(&e.text) {
                return Err(format!("unbound SAME_AS({})", e.text));
            }
        }
        Op::SCOPE => validate(&e.children[0], &mut HashSet::new())?,
        Op::LOOK | Op::NOT => validate(&e.children[0], &mut bound.clone())?,
        Op::CAPTURE => {
            validate(&e.children[0], bound)?;
            bound.insert(e.text.clone());
        }
        Op::CHOICE => {
            let mut definite: Option<HashSet<String>> = None;
            for child in &e.children {
                let mut branch = bound.clone();
                validate(child, &mut branch)?;
                if let Some(ref mut definite) = definite {
                    definite.retain(|n| branch.contains(n));
                } else {
                    definite = Some(branch);
                }
            }
            if let Some(definite) = definite {
                bound.extend(definite);
            }
        }
        Op::REPEAT => {
            let child = &e.children[0];
            if e.max < 0 && child.nullable() {
                return Err("nullable unbounded lexical repeat".into());
            }
            let mut iteration = bound.clone();
            validate(child, &mut iteration)?;
            if e.min > 0 {
                bound.extend(iteration);
            }
        }
        _ => {
            for child in &e.children {
                validate(child, bound)?;
            }
        }
    }
    Ok(())
}
fn convert(e: &Syntax) -> Program {
    let op = match e.op {
        Op::LITERAL => ProgramOp::LITERAL,
        Op::ANY => ProgramOp::ANY,
        Op::EOF => ProgramOp::EOF,
        Op::BOF => ProgramOp::BOF,
        Op::BOL => ProgramOp::BOL,
        Op::EOL => ProgramOp::EOL,
        Op::RANGE => ProgramOp::RANGE,
        Op::EXCEPT => ProgramOp::EXCEPT,
        Op::SEQUENCE => ProgramOp::SEQUENCE,
        Op::CHOICE => ProgramOp::CHOICE,
        Op::REPEAT => ProgramOp::REPEAT,
        Op::LOOK => ProgramOp::LOOK,
        Op::NOT => ProgramOp::NOT,
        Op::CAPTURE => ProgramOp::CAPTURE,
        Op::BACKREF => ProgramOp::BACKREF,
        Op::REF => ProgramOp::REF,
        Op::SCOPE => ProgramOp::SCOPE,
    };
    Program {
        op,
        text: e.text.clone(),
        min: e.min,
        max: e.max,
        children: e.children.iter().map(convert).collect(),
    }
}
