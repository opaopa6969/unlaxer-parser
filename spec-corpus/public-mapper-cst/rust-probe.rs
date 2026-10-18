#[path="generated/mod.rs"] mod generated;
#[path="dag/mod.rs"] mod dag;
use unlaxer_runtime::{Node, Tree};
fn parsed(input:&str)->Tree { generated::parser::parse_tree_for_mapping(input).unwrap() }
fn corrupted(input:&str,id:&str)->Tree {
    let mut tree=parsed(input);
    match id {
        "null-root"=>tree.root=usize::MAX,
        "metadata"=>tree.nodes[tree.root].rule=1_000_000,
        "child"=>tree.nodes[tree.root].children.push(usize::MAX),
        "cycle"=>tree.nodes[tree.root].children.push(tree.root),
        "capture-edge"=> {let id=tree.nodes.iter().position(|n|n.captures.iter().any(|c|!c.nodes.is_empty())).unwrap();tree.nodes[id].children.clear();},
        "span"=>tree.nodes[tree.root].span.end=usize::MAX,
        "depth"=>for _ in 0..256 {let node=Node{rule:tree.nodes[tree.root].rule,span:tree.nodes[tree.root].span,children:vec![tree.root],captures:vec![]};tree.nodes.push(node);tree.root=tree.nodes.len()-1;},
        _=>panic!("unknown fixture")
    }
    tree
}
fn check_dag() {
    let original=dag::parser::parse_tree_for_mapping("😀").unwrap();
    let mut tree=original.clone();
    for _ in 0..__DAG_DEPTH__ {
        let previous=tree.root;
        let mut node=tree.nodes[previous].clone();
        node.children=vec![previous,previous];
        node.captures.retain(|c|!c.name.starts_with("#preferred-depth:"));
        node.captures.push(unlaxer_runtime::Capture{name:"#preferred-depth:2",span:node.span,nodes:vec![previous]});
        tree.nodes.push(node);tree.root=tree.nodes.len()-1;
    }
    let error=dag::mapper::map_parsed_tree(&tree).err().expect("unbounded DAG snapshot");
    assert!(error.contains("limit") || error.contains("E-MAPPING-LIMIT"),"{error}");
    assert!(dag::mapper::map_parsed_tree(&original).is_ok());
    // No output nodes: exercise the ordinary mapper's exponential walk without allocating ASTs.
    let root_rule=original.nodes[original.root].rule;
    for node in &mut tree.nodes { node.rule=root_rule; node.captures.clear(); }
    let ordinary=dag::mapper::map(&tree).err().expect("unbounded ordinary DAG mapping");
    assert!(ordinary.contains("E-MAPPING-LIMIT"),"{ordinary}");
}
fn main() {
    check_dag();
    let input="[😀😀]";
    let retained=generated::mapper::map_parsed_tree(&parsed(input)).unwrap();
    let expected=generated::mapper::map(&parsed(input)).unwrap().canonical_json();
    for id in ["null-root","metadata","child","cycle","capture-edge","depth","span"] {
        for entry in ["ordinary","source-map","subtree","subtree-source-map","preferred","subtree-preferred"] {
            let bad=corrupted(input,id);
            let error=match entry {
                "ordinary"|"source-map"=>generated::mapper::map(&bad).err(),
                "subtree"|"subtree-source-map"|"subtree-preferred"=>generated::mapper::map_subtree_tree(&bad,bad.root).err(),
                "preferred"=>generated::mapper::map_parsed_tree(&bad).err(),
                _=>unreachable!(),
            }.expect("invalid CST accepted");
            println!("{id}\t{entry}\t{}",error.split(':').next().unwrap());
            let good=parsed(input);
            let ast=generated::mapper::map(&good).unwrap();
            assert_eq!(ast.canonical_json(),expected);
            let generated::ast::Ast::Root{span,value:Some(values)}=&ast else {panic!("root shape")};
            assert_eq!(*span,unlaxer_runtime::Span{start:__ROOT_START__,end:__ROOT_END__});
            assert_eq!(values.len(),2);
            for (index,value) in values.iter().enumerate() {
                let generated::ast::Ast::Leaf{span,text}=value.as_ref() else {panic!("leaf shape")};
                assert_eq!(text,"😀");
                let spans=[__LEAF_SPANS__];
                assert_eq!((span.start,span.end),spans[index]);
            }
            let snapshot=generated::mapper::map_parsed_tree(&good).unwrap();
            assert_eq!(snapshot.candidates().len(),3);
            assert_eq!(snapshot.select_default().span(),unlaxer_runtime::Span{start:0,end:4});
            assert_eq!(snapshot.source_text(unlaxer_runtime::Span{start:1,end:2}),Some("😀"));
            assert_eq!(retained.select_default().ast().canonical_json(),expected);
        }
    }
}
