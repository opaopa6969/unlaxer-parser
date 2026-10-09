use std::collections::{BTreeMap, BTreeSet};
use unlaxer_runtime::type_system::*;

#[test]
fn deeply_nested_relations_return_a_limit_on_the_default_test_stack() {
    let mut definitions = vec![];
    for i in 0..256 {
        definitions.push(Definition {
            name: format!("T{i}"),
            parameters: vec![],
            variance: vec![],
            parents: if i == 255 {
                vec![]
            } else {
                vec![TypeRef::named(format!("T{}", i + 1), vec![]).unwrap()]
            },
            fields: BTreeMap::new(),
            structural: false,
        });
    }
    let provider = DeclaredProvider::new(
        Policy::Nominal,
        definitions,
        BTreeSet::from([Capability::Named]),
    )
    .unwrap();
    let system = TypeSystem::new(provider, vec![], 4096).unwrap();
    let result = system.compare(
        &TypeRef::named("T0".into(), vec![]).unwrap(),
        &TypeRef::named("T255".into(), vec![]).unwrap(),
    );
    assert_eq!(Status::Limit, result.status);
}
