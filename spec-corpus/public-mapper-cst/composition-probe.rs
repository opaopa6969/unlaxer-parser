#[path="__MODULE__/mod.rs"] mod generated;
use unlaxer_runtime::{Tree,Node,Span,TOKEN_META_RULE,RECOVERY_ERROR_RULE};
fn call(tree:&Tree,entry:&str)->Result<(),String>{match entry{
    "ordinary"|"source-map"=>generated::mapper::map(tree).map(|_|()),
    "subtree"|"subtree-source-map"|"subtree-preferred"=>generated::mapper::map_subtree_tree(tree,tree.root).map(|_|()),
    "preferred"=>generated::mapper::map_parsed_tree(tree).map(|_|()),
    _=>unreachable!(),
}}
fn main(){
    let args:Vec<_>=std::env::args().skip(1).collect();let input=&args[0];let expected=&args[1];let kind=&args[2];
    let mut good=generated::parser::parse_tree_for_mapping(input).unwrap();
    if kind=="recovery"{
        let original=good.nodes[good.root].clone();let mut dag=good.nodes.len();
        good.nodes.push(Node{rule:TOKEN_META_RULE,span:Span{start:0,end:0},children:vec![],captures:vec![]});
        for _ in 0..6{let next=good.nodes.len();good.nodes.push(Node{rule:TOKEN_META_RULE,span:Span{start:0,end:0},children:vec![dag,dag],captures:vec![]});dag=next;}
        let mut root=original;root.children.push(dag);good.root=good.nodes.len();good.nodes.push(root);
    }
    let retained=generated::mapper::map_parsed_tree(&good).unwrap();
    assert_eq!(generated::mapper::map(&good).unwrap().canonical_json(),*expected);
    for entry in ["ordinary","source-map","subtree","subtree-source-map","preferred","subtree-preferred"]{
        let mut bad=good.clone();bad.nodes[bad.root].rule=1_000_000;
        assert!(call(&bad,entry).unwrap_err().starts_with("E-MAPPING-CST"));
        call(&good,entry).unwrap();let actual=generated::mapper::map(&good).unwrap().canonical_json();assert_eq!(actual,*expected);
        assert_eq!(retained.select_default().ast().canonical_json(),*expected);
        println!("{entry}\tE-MAPPING-CST\t{actual}");
    }
    if kind=="typeof"{
        let mut bad=good.clone();let alias=bad.nodes.iter().position(|n|n.rule==1).unwrap();
        let capture=bad.nodes[alias].captures.iter().find(|c|c.name=="value").unwrap().clone();bad.nodes[alias].captures.push(capture);
        // Root scan8 + root map8 + alias10 + leaf3, then two projected references must also be charged.
        let limits=generated::mapper::MappingLimits{candidates:4096,projection_work:29};
        let error=generated::mapper::map_subtree_tree_with_limits(&bad,bad.root,limits).err().unwrap();assert!(error.starts_with("E-MAPPING-LIMIT"),"{error}");
        let enough=generated::mapper::MappingLimits{candidates:4096,projection_work:31};
        let error=generated::mapper::map_subtree_tree_with_limits(&bad,bad.root,enough).err().unwrap();assert!(error.contains("expected one value for value, got 2"),"{error}");
        assert_eq!(generated::mapper::map(&good).unwrap().canonical_json(),*expected);
    }
    if kind=="recovery"{
        let mut bad=good.clone();let original=bad.nodes[bad.root].clone();let marker=bad.nodes.len();
        bad.nodes.push(Node{rule:RECOVERY_ERROR_RULE,span:Span{start:0,end:0},children:vec![],captures:vec![]});
        let mut root=original;root.children.push(marker);bad.root=bad.nodes.len();bad.nodes.push(root);
        for entry in ["ordinary","source-map","subtree","subtree-source-map","preferred","subtree-preferred"]{assert_eq!(call(&bad,entry).unwrap_err(),"cannot map recovered syntax");}
        assert_eq!(generated::mapper::map(&good).unwrap().canonical_json(),*expected);
    }
}
