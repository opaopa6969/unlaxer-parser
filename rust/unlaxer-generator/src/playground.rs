//! Self-contained playground project. Assets are shared byte-for-byte with the Java host.
use std::path::Path;
use unlaxer_codegen::GeneratedFile;
use unlaxer_ubnf::ast::TokenKind;

pub fn generate_file(path: &Path) -> Result<Vec<GeneratedFile>, String> {
    let source = std::fs::read_to_string(path).map_err(|error| error.to_string())?;
    let readiness = crate::portability::check_file(path);
    if !readiness.portable {
        return Err(format!(
            "Playground requires a Rust-portable grammar: {}",
            readiness.to_json()
        ));
    }
    let file = crate::modules::load(path)?;
    let grammar = &file.grammars[0];
    for token in &grammar.tokens {
        if !matches!(token.kind, TokenKind::Declarative { .. }) {
            return Err(format!("Playground requires declarative token {} ::= expression; (host bindings are not executed)", token.name));
        }
    }
    let ir = crate::lowering::lower(grammar)?;
    let mut files = crate::generate_grammar(grammar)?;
    for file in &mut files {
        file.relative_path = format!("src/generated/{}", file.relative_path);
    }
    macro_rules! asset {
        ($target:literal, $source:literal) => {
            files.push(GeneratedFile {
                relative_path: $target.into(),
                content: include_str!(concat!("../../../unlaxer-dsl/src/main/resources/", $source))
                    .into(),
            });
        };
    }
    asset!("Cargo.toml", "playground/Cargo.toml");
    asset!("package.json", "playground/package.json");
    asset!("build.mjs", "playground/build.mjs");
    asset!("serve.mjs", "playground/serve.mjs");
    asset!("README.txt", "playground/README.txt");
    asset!(".cargo/config.toml", "playground/config.toml");
    asset!("runtime/Cargo.toml", "playground/runtime-Cargo.toml");
    files.push(GeneratedFile {
        relative_path: "runtime/LICENSE".into(),
        content: include_str!("../../../LICENSE").into(),
    });
    macro_rules! runtime {
        ($name:literal) => {
            files.push(GeneratedFile {
                relative_path: concat!("runtime/src/", $name).into(),
                content: include_str!(concat!("../../unlaxer-runtime/src/", $name)).into(),
            });
        };
    }
    runtime!("lib.rs");
    runtime!("scope.rs");
    runtime!("semantic.rs");
    runtime!("language_queries.rs");
    runtime!("semantic_queries.rs");
    runtime!("semantic_project.rs");
    runtime!("semantic_query_cache.rs");
    runtime!("type_system.rs");
    runtime!("call_inference.rs");
    runtime!("semantic_rename.rs");
    runtime!("source_edits.rs");
    runtime!("source.rs");
    runtime!("embedded.rs");
    runtime!("pipeline.rs");
    runtime!("lexing.rs");
    runtime!("first.rs");
    runtime!("lexical.rs");
    runtime!("long_code_fence.rs");
    runtime!("memo_retention_tests.rs");
    let wrapper = include_str!("../../../unlaxer-dsl/src/main/resources/playground/lib.rs")
        .replace(
            "@@DOCS@@",
            if ir.rules.iter().any(|rule| !rule.documentation.is_empty()) {
                "generated::parser::RULE_DOCS"
            } else {
                "&[]"
            },
        )
        .replace("@@NAME@@", &format!("{:?}", grammar.name))
        .replace("@@ROOT@@", &ir.root.to_string());
    files.push(GeneratedFile {
        relative_path: "src/lib.rs".into(),
        content: wrapper,
    });
    asset!("public/index.html", "playground/index.html");
    asset!("public/playground.css", "playground/playground.css");
    asset!("public/playground.js", "playground/playground.js");
    asset!("public/worker.js", "playground/worker.js");
    asset!("public/help/index.html", "ubnf-help/index.html");
    asset!("public/help/help.css", "ubnf-help/help.css");
    asset!("public/help/help.js", "ubnf-help/help.js");
    asset!("public/help/catalog.json", "ubnf-help/catalog.json");
    files.push(GeneratedFile {
        relative_path: "public/grammar.ubnf".into(),
        content: source,
    });
    Ok(files)
}
