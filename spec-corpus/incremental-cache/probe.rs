mod cache;
mod other;
mod recovered;
use unlaxer_runtime::{json_string, Tree};
use unlaxer_runtime::incremental::{SnapshotParseCache, Revisions};
use unlaxer_runtime::source::Snapshot;
fn fresh(grammar: &str, profile: &str, state: &str, input: &str) -> String {
    let grammar = if profile != "normal" || state != "normal" { "Other" } else { grammar };
    let parsed = match grammar {
        "Cache" => cache::parser::parse_tree_detailed(input),
        "Other" => other::parser::parse_tree_detailed(input),
        "Recovered" => recovered::parser::parse_tree_detailed(input),
        _ => panic!("unknown fixture grammar"),
    };
    match parsed {
        Err(error) => {
            let snapshot=Snapshot::new("fixture",1,input).unwrap();
            format!(r#"{{"accepted":false,"cst":null,"span":null,"ast":null,"diagnostic":{},"coordinates":[{},{},{}],"recoveries":[]}}"#,error.offset,error.offset,snapshot.utf16(error.offset).unwrap(),snapshot.utf8(error.offset).unwrap())
        },
        Ok(tree) => {
            let span = tree.nodes[tree.root].span;
            let recoveries = tree.recoveries().iter().map(|r|format!("[{},{}]",r.span.start,r.span.end)).collect::<Vec<_>>().join(",");
            let ast = if tree.recoveries().is_empty() { mapped(grammar,&tree) } else {
                assert!(recovered::mapper::map(&tree).is_err()); "null".into()
            };
            format!(r#"{{"accepted":true,"cst":{},"span":[{},{}],"ast":{},"diagnostic":null,"coordinates":[{},{},{}],"recoveries":[{}]}}"#,
                json_string(tree.text(span)),span.start,span.end,ast,span.end,input.encode_utf16().count(),input.len(),recoveries)
        }
    }
}
fn mapped(grammar: &str,tree: &Tree) -> String {
    match grammar {
        "Cache" => cache::mapper::map(tree).unwrap().canonical_json(),
        "Other" => other::mapper::map(tree).unwrap().canonical_json(),
        "Recovered" => recovered::mapper::map(tree).unwrap().canonical_json(),
        _ => unreachable!(),
    }
}
fn observe(cache: &mut SnapshotParseCache<String>,uri: &str,version: u64,grammar: &str,profile: &str,state: &str,input: &str) {
    let snapshot=Snapshot::new(uri,version,input).unwrap();
    let revisions=Revisions{grammar:grammar.into(),profile:profile.into(),state:state.into()};
    let hits=cache.stats().hits;
    let result=cache.get_or_try_compute(&snapshot,&revisions,||Ok::<_,()>(fresh(grammar,profile,state,input))).unwrap();
    assert_eq!(result,fresh(grammar,profile,state,input));
    println!(r#"{{"hit":{},"result":{}}}"#,cache.stats().hits>hits,result);
}
