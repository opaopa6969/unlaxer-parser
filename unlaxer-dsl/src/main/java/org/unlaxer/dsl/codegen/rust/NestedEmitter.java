package org.unlaxer.dsl.codegen.rust;

import org.unlaxer.dsl.codegen.rust.GrammarIR.*;

final class NestedEmitter {
    private NestedEmitter() {}
    static String type(NestedShape shape) {
        String type=switch(shape.leaf()) { case INTEGER -> "i32"; case NODE -> "Box<Ast>"; case ENUM -> shape.enumName(); case VALUE -> "NestedValue"; default -> "String"; };
        for(int i=shape.containers().size()-1;i>=0;i--) type=(shape.containers().get(i)==Cardinality.OPTIONAL?"Option":"Vec")+"<"+type+">";
        return type;
    }
    static String json(NestedShape shape,String value,int depth) {
        if(depth==shape.containers().size()) return shape.leaf()==Kind.INTEGER?value+".to_string()":shape.leaf()==Kind.NODE || shape.leaf()==Kind.ENUM || shape.leaf()==Kind.VALUE?value+".canonical_json()":"json_string("+value+")";
        String child="nested_"+depth, inner=json(shape,child,depth+1);
        return shape.containers().get(depth)==Cardinality.OPTIONAL
            ?value+".as_ref().map_or_else(|| \"null\".to_owned(), |"+child+"| "+inner+")"
            :"format!(\"[{}]\", "+value+".iter().map(|"+child+"| "+inner+").collect::<Vec<_>>().join(\",\"))";
    }
    static String mapped(NestedShape shape,String value,int depth,String projection) {
        if(depth==shape.containers().size() && projection!=null) return "nested_project(tree, "+value+", &mut nested_sources, "+projection+")"+(shape.leaf()==Kind.NODE?".map(Box::new)":"");
        if(depth==shape.containers().size()) return "nested_"+(shape.leaf()==Kind.INTEGER?"integer":"text")+"("+value+")";
        String child="nested_"+depth;
        return "nested_"+(shape.containers().get(depth)==Cardinality.OPTIONAL?"optional":"list")+"("+value+", |"+child+"| "+mapped(shape,child,depth+1,projection)+")";
    }
    static boolean dapNode(Field field) { return field.kind()==Kind.NESTED && (field.nested().leaf()==Kind.NODE || field.nested().leaf()==Kind.VALUE); }
    static String dapWalk(NestedShape shape,String value,int depth) {
        if(depth==shape.containers().size())return shape.leaf()==Kind.NODE?"collect("+value+", out);":"if let super::ast::NestedValue::Node(child) = "+value+" { collect(child, out); }";
        String child="nested_"+depth;
        return (shape.containers().get(depth)==Cardinality.OPTIONAL?"if let Some("+child+") = ":"for "+child+" in ")+value+" { "+dapWalk(shape,child,depth+1)+" }";
    }
    static String mixedMapper(GrammarIR ir) {
        var out=new StringBuilder("\nfn map_nested_values(tree: &Tree, ids: &[usize]) -> Result<Vec<NestedValue>, String> {\n    let mut found = Vec::new();\n    for &id in ids {\n        tree.visit(id)?;\n        let node = &tree.nodes[id];\n        match node.rule {\n            unlaxer_runtime::TEXT_VALUE_RULE => found.push(NestedValue::Text { text: tree.text(node.span)?.to_owned(), span: node.span }),\n            unlaxer_runtime::VALUE_BOUNDARY_RULE => found.push(NestedValue::Integer { value: tree.text(node.span)?.trim_matches(|ch: char| ch <= '\\u{20}').parse().map_err(|_| format!(\"invalid i32 nested mixed capture at {}..{}\", node.span.start, node.span.end))?, span: node.span }),\n");
        for(int id=0;id<ir.rules().size();id++) {
            var rule=ir.rules().get(id);
            if(!rule.enumLiterals().isEmpty())out.append("            ").append(id).append(" => found.push(NestedValue::Enum").append(rule.name()).append(" { value: ").append(rule.name()).append("::from_text(tree.text(node.span)?)?, span: node.span }),\n");
        }
        var ids=new java.util.ArrayList<String>();
        for(int id=0;id<ir.rules().size();id++) {
            var rule=ir.rules().get(id);
            if((rule.mapping()!=null||!rule.typeConstraints().isEmpty())&&rule.enumLiterals().isEmpty())ids.add(Integer.toString(id));
        }
        out.append("            ").append(String.join(" | ",ids)).append(" => found.extend(map_node(tree, id)?.into_iter().map(|node| NestedValue::Node(Box::new(node)))),\n");
        return out.append("            _ => found.extend(map_nested_values(tree, &node.children)?),\n        }\n    }\n    Ok(found)\n}\n").toString();
    }
    static final String HELPERS = "\nfn nested_capture(tree: &Tree, capture: &unlaxer_runtime::Capture) -> Result<unlaxer_runtime::capture_tree::Element, String> {\n    if capture.nodes.len() != 1 { return Err(format!(\"invalid nested capture {} at {}..{}\", capture.name, capture.span.start, capture.span.end)); }\n    let snapshot = unlaxer_runtime::capture_tree::snapshot(tree, capture.nodes[0]).map_err(|error| format!(\"invalid nested capture {} at {}..{}: {error:?}\", capture.name, capture.span.start, capture.span.end))?;\n    let mut pending = vec![&snapshot];\n    while let Some(value) = pending.pop() {\n        tree.charge_work(1 + value.children().len())?;\n        tree.charge_text_bytes(value.text().len())?;\n        pending.extend(value.children());\n    }\n    Ok(snapshot)\n}\nfn nested_text(value: &unlaxer_runtime::capture_tree::Element) -> Result<String, String> {\n    if value.kind() != unlaxer_runtime::capture_tree::Kind::Value { return Err(\"expected nested value boundary\".into()); }\n    Ok(value.text().to_owned())\n}\nfn nested_integer(value: &unlaxer_runtime::capture_tree::Element) -> Result<i32, String> {\n    nested_text(value)?.trim_matches(|ch: char| ch <= '\\u{20}').parse().map_err(|_| format!(\"invalid i32 nested capture at {}..{}\", value.span().start, value.span().end))\n}\nfn nested_optional<T>(value: &unlaxer_runtime::capture_tree::Element, map: impl FnMut(&unlaxer_runtime::capture_tree::Element) -> Result<T, String>) -> Result<Option<T>, String> {\n    if value.kind() != unlaxer_runtime::capture_tree::Kind::Optional || value.children().len() > 1 { return Err(\"expected nested optional boundary\".into()); }\n    value.children().first().map(map).transpose()\n}\nfn nested_list<T>(value: &unlaxer_runtime::capture_tree::Element, map: impl FnMut(&unlaxer_runtime::capture_tree::Element) -> Result<T, String>) -> Result<Vec<T>, String> {\n    if value.kind() != unlaxer_runtime::capture_tree::Kind::List { return Err(\"expected nested list boundary\".into()); }\n    value.children().iter().map(map).collect()\n}\n";
    static final String PROJECT_HELPERS = "\n// A leaf is identified by its original CST node, never by text or source position.\nfn nested_source_nodes(tree: &Tree, root: usize) -> Result<Vec<usize>, String> {\n    let mut pending = vec![(root, 0usize)];\n    let mut values = Vec::new();\n    let mut visits = 0usize;\n    while let Some((id, depth)) = pending.pop() {\n        tree.visit(id)?;\n        visits += 1;\n        if visits > 65536 || depth >= 256 { return Err(\"nested projection traversal limit\".into()); }\n        let node = tree.nodes.get(id).ok_or(\"invalid nested projection node\")?;\n        if unlaxer_runtime::capture_tree::Kind::of_rule(node.rule) == Some(unlaxer_runtime::capture_tree::Kind::Separator) { continue; }\n        if unlaxer_runtime::capture_tree::Kind::of_rule(node.rule) == Some(unlaxer_runtime::capture_tree::Kind::Value) {\n            values.push(id);\n        } else {\n            for &child in node.children.iter().rev() {\n                if visits + pending.len() >= 65536 { return Err(\"nested projection traversal limit\".into()); }\n                pending.push((child, depth + 1));\n            }\n        }\n    }\n    Ok(values)\n}\nfn nested_project<T>(tree: &Tree, value: &unlaxer_runtime::capture_tree::Element, sources: &mut impl Iterator<Item=usize>, map: fn(&Tree, &[usize]) -> Result<Vec<T>, String>) -> Result<T, String> {\n    if value.kind() != unlaxer_runtime::capture_tree::Kind::Value { return Err(\"expected nested mapped value boundary\".into()); }\n    let id = sources.next().ok_or(\"missing original nested leaf\")?;\n    let node = tree.nodes.get(id).ok_or(\"invalid original nested leaf\")?;\n    let mut values = map(tree, &node.children)?;\n    if values.len() != 1 { return Err(format!(\"expected one nested mapped value at {}..{}, got {}\", value.span().start, value.span().end, values.len())); }\n    Ok(values.remove(0))\n}\n";
}
