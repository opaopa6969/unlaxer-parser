use std::io::{self, BufRead};
use unlaxer_evolution_example::{generated, semantics::Calculator};

fn main() {
    for line in io::stdin().lock().lines() {
        let input = line.expect("read input");
        match generated::parser::parse_tree_detailed(&input) {
            Ok(tree) => match generated::mapper::map(&tree) {
                Ok(ast) => {
                    let value = generated::evaluator::evaluate(&ast, &mut Calculator);
                    match value {
                        Ok(value) if value.is_finite() => println!(
                            "{{\"ok\":true,\"ast\":{},\"value\":{value}}}",
                            ast.canonical_json()
                        ),
                        _ => println!(
                            "{{\"ok\":true,\"ast\":{},\"evaluationError\":true}}",
                            ast.canonical_json()
                        ),
                    }
                }
                Err(error) => panic!("generated mapper invariant: {error}"),
            },
            Err(error) => println!(
                "{{\"ok\":false,\"offset\":{},\"expected\":[{}],\"diagnostic\":{}}}",
                error.farthest.offset,
                error
                    .farthest
                    .expected
                    .iter()
                    .map(|s| unlaxer_runtime::json_string(s))
                    .collect::<Vec<_>>()
                    .join(","),
                error.canonical_json()
            ),
        }
    }
}
