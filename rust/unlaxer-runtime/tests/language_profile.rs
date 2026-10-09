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

#[test]
fn shared_selection_and_capability_fixtures() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let source = std::fs::read_to_string(root.join("language-profiles/java/profile.tsv")).unwrap();
    let fixtures =
        std::fs::read_to_string(root.join("docs/fixtures/language-profiles/selection.tsv"))
            .unwrap();
    for row in fixtures.lines() {
        let fields: Vec<_> = row.split('\t').collect();
        let changed = if fields[1] == "-" {
            source.clone()
        } else {
            source.replace(
                &fields[1].replace("\\t", "\t"),
                &fields[2].replace("\\t", "\t"),
            )
        };
        let profile = LanguageProfile::parse(&changed).unwrap();
        let selected = profile.select(fields[3], fields[4]);
        assert_eq!(selected.is_ok(), fields[5] == "true", "{}", fields[0]);
        if let Ok(selected) = selected {
            assert_eq!(
                selected.allows_local("COMPLETION"),
                fields[6] == "true",
                "{}",
                fields[0]
            );
            assert_eq!(
                selected.allows_local("DEFINITION"),
                fields[7] == "true",
                "{}",
                fields[0]
            );
            assert!(selected.allows_local("PARSE"));
            assert!(!selected.allows_local("UNKNOWN"));
            assert_eq!(selected.language.package_id, "lang/java");
            assert_eq!(selected.language.version, "0.1.0");
        }
    }
}
