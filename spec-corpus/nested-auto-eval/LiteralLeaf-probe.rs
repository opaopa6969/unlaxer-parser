use generated::{
    ast::Ast,
    evaluator::{evaluate, EvalValue, Semantics},
};
use serde_json::{json, Value};
use unlaxer_runtime::{Diagnostics, Memoization, ParseOptions, Span};
#[derive(Debug)]
struct Failure(Span);
#[derive(Default)]
struct Probe {
    events: Vec<String>,
}
impl Semantics for Probe {
    type Output = Result<Vec<Option<String>>, Failure>;
    fn eval_root(&mut self, value: &Vec<Option<Box<Ast>>>, span: Span) -> Self::Output {
        self.events
            .push(format!("root@{}:{}", span.start, span.end));
        let mut result = Vec::new();
        for child in value {
            match child {
                Some(node) => result.extend(evaluate(node, self)?),
                None => {
                    self.events.push("none".into());
                    result.push(None);
                }
            }
        }
        Ok(result)
    }
    fn convert_value(&mut self, value: EvalValue<'_>, span: Span) -> Self::Output {
        let EvalValue::Text(text) = value else {
            panic!("literal must preserve Text input");
        };
        self.events
            .push(format!("literal@{}:{}={}", span.start, span.end, text));
        if text == "!" {
            return Err(Failure(span));
        }
        Ok(vec![Some(text.into())])
    }
}
fn observation(ast: &Ast) -> Value {
    let mut probe = Probe::default();
    let result = evaluate(ast, &mut probe);
    let span = ast.span();
    let mut output = json!({"span":[span.start, span.end], "events":probe.events});
    match result {
        Ok(value) => output["value"] = json!(value),
        Err(Failure(at)) => {
            output["error"] = json!("requested failure");
            output["error_span"] = json!([at.start, at.end]);
        }
    }
    output
}
fn main() {
    let cases: Value =
        serde_json::from_str(&std::fs::read_to_string(std::env::args().nth(1).unwrap()).unwrap())
            .unwrap();
    for row in cases.as_array().unwrap() {
        let source = row["input"].as_str().unwrap();
        for memoization in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let options = ParseOptions {
                    memoization,
                    diagnostics,
                    ..ParseOptions::default()
                };
                let parsed =
                    generated::parser::parse_tree_for_mapping_with_options(source, options);
                if row.get("error").is_some() {
                    let error = parsed.unwrap_err();
                    assert_eq!(
                        error.offset,
                        row["error_cp"].as_u64().unwrap() as usize,
                        "{}",
                        row["id"]
                    );
                    println!(
                        "NESTED_AUTO\t{}\t{:?}\t{:?}\t-\t-\tfull-input-rejected@{}",
                        row["id"], memoization, diagnostics, error.offset
                    );
                    continue;
                }
                let tree = parsed.unwrap();
                assert_eq!(
                    tree.nodes[tree.root].span.end,
                    row["consumed_cp"].as_u64().unwrap() as usize
                );
                let snapshot = generated::mapper::map_parsed_tree(&tree).unwrap();
                drop(tree);
                assert_eq!(snapshot.source(), source);
                let original = [
                    snapshot.select_default().ast(),
                    snapshot.select(Some("Leaf")).ast(),
                ];
                for (index, selection) in ["default", "leaf"].into_iter().enumerate() {
                    assert_eq!(
                        observation(original[index]),
                        row[selection],
                        "{} {}",
                        row["id"],
                        selection
                    );
                    println!(
                        "NESTED_AUTO\t{}\t{:?}\t{:?}\t{}\tinitial\tchecked",
                        row["id"], memoization, diagnostics, selection
                    );
                }
                let interference =
                    generated::parser::parse_tree_for_mapping_with_options("!😀", options).unwrap();
                let other = generated::mapper::map_parsed_tree(&interference).unwrap();
                drop(other);
                drop(interference);
                for (index, selection) in ["default", "leaf"].into_iter().enumerate() {
                    let selected = if index == 0 {
                        snapshot.select_default()
                    } else {
                        snapshot.select(Some("Leaf"))
                    };
                    assert!(std::ptr::eq(original[index], selected.ast()));
                    assert_eq!(
                        observation(original[index]),
                        row[selection],
                        "retained {} {}",
                        row["id"],
                        selection
                    );
                    assert_eq!(observation(selected.ast()), row[selection]);
                    println!(
                        "NESTED_AUTO\t{}\t{:?}\t{:?}\t{}\tretained\tchecked",
                        row["id"], memoization, diagnostics, selection
                    );
                }
            }
        }
    }
}
