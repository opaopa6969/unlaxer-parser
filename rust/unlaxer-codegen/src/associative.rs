use crate::*;
pub(super) fn spine(mapping: &Mapping) -> bool {
    mapping.fields.iter().any(|f| f.kind == Kind::NullableNode)
}
pub(super) fn capture(mapping: Option<&Mapping>, field: &Field, indent: &str) -> Option<String> {
    mapping
        .filter(|m| spine(m))
        .filter(|_| matches!(field.name.as_str(), "left" | "right"))
        .map(|m| {
            format!(
                "{indent}values.push(map_associative_{}(tree, capture)?);\n",
                m.name
            )
        })
}
pub(super) fn helpers(ir: &GrammarIr) -> String {
    mappings(ir)
        .into_iter()
        .filter(|m| spine(m))
        .map(|m| {
            include_str!("../../../unlaxer-dsl/src/main/resources/rust/associative-spine.rs.txt")
                .replace("$NAME", &m.name)
        })
        .collect()
}
