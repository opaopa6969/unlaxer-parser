use std::fs;
#[test]
fn profile_generation_rejects_empty_and_mismatched_entry_grammars() {
    let root =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../language-profiles/java");
    let directory =
        std::env::temp_dir().join(format!("unlaxer-profile-invalid-{}", std::process::id()));
    fs::create_dir(&directory).unwrap();
    struct Cleanup(std::path::PathBuf);
    impl Drop for Cleanup {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    let _cleanup = Cleanup(directory.clone());
    fs::copy(root.join("profile.tsv"), directory.join("profile.tsv")).unwrap();
    for source in ["", "grammar Java21 { @root CompilationUnit ::= 'ok'; }"] {
        fs::write(directory.join("Java21.ubnf"), source).unwrap();
        assert!(
            unlaxer_generator::playground::generate_profile(&directory.join("profile.tsv"))
                .is_err()
        );
    }
}
