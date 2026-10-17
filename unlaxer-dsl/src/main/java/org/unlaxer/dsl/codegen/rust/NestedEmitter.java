package org.unlaxer.dsl.codegen.rust;

import org.unlaxer.dsl.codegen.rust.GrammarIR.*;

final class NestedEmitter {
    private NestedEmitter() {}
    static String type(NestedShape shape) {
        String type=shape.leaf()==Kind.INTEGER?"i32":"String";
        for(int i=shape.containers().size()-1;i>=0;i--) type=(shape.containers().get(i)==Cardinality.OPTIONAL?"Option":"Vec")+"<"+type+">";
        return type;
    }
    static String json(NestedShape shape,String value,int depth) {
        if(depth==shape.containers().size()) return shape.leaf()==Kind.INTEGER?value+".to_string()":"json_string("+value+")";
        String child="nested_"+depth, inner=json(shape,child,depth+1);
        return shape.containers().get(depth)==Cardinality.OPTIONAL
            ?value+".as_ref().map_or_else(|| \"null\".to_owned(), |"+child+"| "+inner+")"
            :"format!(\"[{}]\", "+value+".iter().map(|"+child+"| "+inner+").collect::<Vec<_>>().join(\",\"))";
    }
    static String mapped(NestedShape shape,String value,int depth) {
        if(depth==shape.containers().size()) return "nested_"+(shape.leaf()==Kind.INTEGER?"integer":"text")+"("+value+")";
        String child="nested_"+depth;
        return "nested_"+(shape.containers().get(depth)==Cardinality.OPTIONAL?"optional":"list")+"("+value+", |"+child+"| "+mapped(shape,child,depth+1)+")";
    }
    static final String HELPERS = "\nfn nested_capture(tree: &Tree, capture: &unlaxer_runtime::Capture) -> Result<unlaxer_runtime::capture_tree::Element, String> {\n    if capture.nodes.len() != 1 { return Err(format!(\"invalid nested capture {} at {}..{}\", capture.name, capture.span.start, capture.span.end)); }\n    unlaxer_runtime::capture_tree::snapshot(tree, capture.nodes[0]).map_err(|error| format!(\"invalid nested capture {} at {}..{}: {error:?}\", capture.name, capture.span.start, capture.span.end))\n}\nfn nested_text(value: &unlaxer_runtime::capture_tree::Element) -> Result<String, String> {\n    if value.kind() != unlaxer_runtime::capture_tree::Kind::Value { return Err(\"expected nested value boundary\".into()); }\n    Ok(value.text().to_owned())\n}\nfn nested_integer(value: &unlaxer_runtime::capture_tree::Element) -> Result<i32, String> {\n    nested_text(value)?.trim_matches(|ch: char| ch <= '\\u{20}').parse().map_err(|_| format!(\"invalid i32 nested capture at {}..{}\", value.span().start, value.span().end))\n}\nfn nested_optional<T>(value: &unlaxer_runtime::capture_tree::Element, map: impl Fn(&unlaxer_runtime::capture_tree::Element) -> Result<T, String>) -> Result<Option<T>, String> {\n    if value.kind() != unlaxer_runtime::capture_tree::Kind::Optional || value.children().len() > 1 { return Err(\"expected nested optional boundary\".into()); }\n    value.children().first().map(map).transpose()\n}\nfn nested_list<T>(value: &unlaxer_runtime::capture_tree::Element, map: impl Fn(&unlaxer_runtime::capture_tree::Element) -> Result<T, String>) -> Result<Vec<T>, String> {\n    if value.kind() != unlaxer_runtime::capture_tree::Kind::List { return Err(\"expected nested list boundary\".into()); }\n    value.children().iter().map(map).collect()\n}\n";
}
