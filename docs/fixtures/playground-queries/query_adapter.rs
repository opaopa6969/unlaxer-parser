//! Test host: real generated region registry + semantic project + checked edit policies.
#[path = "java/mod.rs"] mod java;
#[path = "tiny/mod.rs"] mod tiny;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use unlaxer_runtime::{Span, embedded::{self, Grammar}, editor_cst::Options,
    language_queries::{LanguageQueries, Project, Provider, Request, Response},
    semantic::{ModelData, Scope, SemanticModel, Symbol, Type, TypeKind},
    semantic_project::{Module, ProjectSymbolIndex}, semantic_queries::ProjectQueryProvider,
    source::{Language, Snapshot, Result, Operation, Edit}, source_edits::{self as edits, SourceEdits, Piece, Kind}};
fn span(start: usize, end: usize) -> Span { Span {start,end} }
fn language(id: &str, grammar: &str, entry: &str) -> Language {
    Language { id:id.into(), package_id:"example".into(), version:"1".into(), grammar:grammar.into(), entry:entry.into() }
}
struct Combined { semantic: ProjectQueryProvider, edits: edits::QueryProvider }
impl Provider for Combined {
    fn capabilities(&self) -> HashSet<Operation> { self.semantic.capabilities().union(&self.edits.capabilities()).copied().collect() }
    fn query(&self, request: &Request<'_>) -> Result<Response> {
        if self.semantic.capabilities().contains(&request.operation) { self.semantic.query(request) } else { self.edits.query(request) }
    }
}
fn inventory(snapshot: Snapshot) -> Result<SourceEdits> {
    let definitions = [(0,3,Kind::Token),(3,4,Kind::Whitespace),(4,9,Kind::Comment),(9,10,Kind::Whitespace),
        (10,13,Kind::Token),(13,14,Kind::Whitespace),(14,17,Kind::Token),(17,18,Kind::Whitespace),(18,19,Kind::Token),(19,20,Kind::Whitespace)];
    SourceEdits::new(snapshot, definitions.iter().enumerate().map(|(i,(start,end,kind))| Piece {
        id:format!("p{i}"), span:span(*start,*end), kind:*kind, owner:String::new() }).collect())
}
pub fn bind(host: &Snapshot) -> Result<Option<LanguageQueries>> {
    let formula = crate::generated::parser::embedded_editor_grammar(vec!["}F".into()],Options::default());
    let tiny = tiny::parser::embedded_editor_grammar(vec!["]T".into()],Options::default());
    let java = java::parser::embedded_editor_grammar(vec![";".into()], Options::default());
    let root = language("formula","FormulaInfo","Document");
    let child_language = language("java","Java","CompilationUnit");
    let grammars: HashMap<Language,&dyn Grammar> = HashMap::from([
        (root.clone(), &formula as &dyn Grammar), (language("tiny","TinyExpression","Expression"), &tiny as &dyn Grammar),
        (child_language.clone(), &java as &dyn Grammar)]);
    let parsed = embedded::parse(host,&root,&grammars,8,32)?;
    let tree = parsed.tree()?;
    let project = Project { id:"p".into(), version:host.version, documents:BTreeMap::from([(host.uri.clone(),host.clone())]), configuration:BTreeMap::new() };
    let mut providers: HashMap<Language,Box<dyn Provider>> = HashMap::new();
    if let Some(region) = parsed.regions.iter().find(|r| r.language == child_language && r.source_map.output().text == "foo /*😀*/ far foo f ") {
        let snapshot = region.source_map.output().clone();
        let version = i64::try_from(host.version).map_err(|_| "version outside semantic range")?;
        let model = SemanticModel::new(snapshot.uri.clone(), version, snapshot.text.clone(), ModelData {
            types:vec![Type{id:"T".into(),kind:TypeKind::Builtin,supertypes:vec![],fields:vec![],span:span(0,0)}],
            scopes:vec![Scope{id:"root".into(),parent:None,span:span(0,snapshot.len())}],
            symbols:vec![Symbol{id:"foo".into(),name:"foo".into(),type_id:"T".into(),scope:"root".into(),declaration:span(0,3),visible_from:3},
                Symbol{id:"far".into(),name:"far".into(),type_id:"T".into(),scope:"root".into(),declaration:span(10,13),visible_from:13}], signatures:vec![],calls:vec![] }).map_err(|_| "invalid semantic model")?;
        let index = ProjectSymbolIndex::new("p".into(),version,vec![Module{id:"inner".into(),model,exports:BTreeSet::new(),imports:vec![]}],vec![]).map_err(|_| "invalid semantic project")?;
        let mut policies: HashMap<edits::Operation,edits::EditPolicy> = HashMap::new();
        for operation in [edits::Operation::Rename,edits::Operation::Format,edits::Operation::CodeAction] {
            let original=snapshot.clone();
            policies.insert(operation,Box::new(move |request| {
                let replacements=match operation {
                    edits::Operation::Rename => { let name=request.parameters.get("newName").ok_or("name missing")?;
                        if name.is_empty() || !name.chars().all(char::is_alphabetic) { return Err("invalid name"); }
                        vec![Edit{span:span(0,3),replacement:name.clone()},Edit{span:span(14,17),replacement:name.clone()}] },
                    edits::Operation::Format => vec![Edit{span:span(3,4),replacement:"\t".into()}],
                    edits::Operation::CodeAction => vec![Edit{span:span(18,19),replacement:"foo".into()}]
                };
                inventory(original.clone())?.plan(operation,span(0,original.len()),replacements)
            }));
        }
        let edit_provider=edits::QueryProvider::new(inventory(snapshot)?,child_language.clone(),project.clone(),policies);
        providers.insert(child_language,Box::new(Combined{semantic:ProjectQueryProvider::new(index),edits:edit_provider}));
    }
    Ok(Some(LanguageQueries::new(tree,project,providers)?))
}
