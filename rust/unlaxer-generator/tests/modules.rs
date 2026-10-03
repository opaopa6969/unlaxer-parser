use std::path::Path;
use std::process::Command;

#[test]
fn local_modules_generate_without_java_and_do_not_change_string_api() {
    let root =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../spec-corpus/lexical-modules/root.ubnf");
    let file = unlaxer_generator::modules::load(&root).unwrap();
    let grammar = &file.grammars[0];
    assert!(grammar.imports.is_empty());
    assert_eq!(grammar.settings.len(), 2);
    assert!(unlaxer_generator::portability::check_file(&root).portable);
    assert!(
        !unlaxer_generator::portability::check(&std::fs::read_to_string(&root).unwrap()).portable
    );
    let files = unlaxer_generator::generate_file(&root).unwrap();
    assert_eq!(files.len(), 5);
    let result = Command::new(env!("CARGO_BIN_EXE_unlaxer"))
        .args(["check", "--target", "rust", "--grammar"])
        .arg(root)
        .env("PATH", "")
        .env("JAVA_HOME", "/missing-java")
        .output()
        .unwrap();
    assert!(result.status.success(), "{:?}", result);
    assert!(String::from_utf8(result.stdout)
        .unwrap()
        .contains("\"portable\":true"));
}
