use unlaxer_runtime::language_profile::{LanguageProfile, Support};
#[test]
fn fixed_profiles_and_malformed_variants_match_the_host_contract() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../language-profiles");
    for language in ["java", "typescript", "rust"] {
        let text = std::fs::read_to_string(root.join(language).join("profile.tsv")).unwrap();
        let profile = LanguageProfile::parse(&text).unwrap();
        assert_eq!(profile.language(), language);
        assert_eq!(profile.capabilities["EXECUTE"], Support::Unsupported);
        assert_eq!(
            profile
                .identity(profile.entries.keys().next().unwrap())
                .unwrap()
                .package_id,
            format!("lang/{language}")
        );
        assert_eq!(
            profile.canonical_tsv(),
            LanguageProfile::parse(&profile.canonical_tsv())
                .unwrap()
                .canonical_tsv()
        );
        for malformed in [
            format!("{text}\n"),
            format!("{text}language\tjava\n"),
            text.replace("0.1.0", "latest"),
            text.replace("EXECUTE\tUNSUPPORTED", "EXECUTE\tSUPPORTED"),
            text.replace("corpus.tsv", "../corpus.tsv"),
            text.replace("profile\t1", "profile\t2"),
            text.replace("capability\tFORMAT\tUNSUPPORTED\n", ""),
        ] {
            assert!(LanguageProfile::parse(&malformed).is_err());
        }
        assert!(profile.identity("Unknown").is_err());
    }
}
