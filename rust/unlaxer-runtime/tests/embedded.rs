use std::collections::HashMap;
use unlaxer_runtime::embedded::{self, Child, Grammar, Parsed};
use unlaxer_runtime::source::{Language, Result, Snapshot, State};
use unlaxer_runtime::Span;
fn language() -> Language {
    Language {
        id: "x".into(),
        package_id: "example".into(),
        version: "1".into(),
        grammar: "G".into(),
        entry: "Document".into(),
    }
}
struct Provider(&'static str);
impl Grammar for Provider {
    fn name(&self) -> &str {
        if self.0 == "wrong-name" {
            "Other"
        } else {
            "G"
        }
    }
    fn parse(&self, _: &str, snapshot: &Snapshot) -> Result<Parsed> {
        let mut source = snapshot.clone();
        if self.0 == "stale" {
            source.version += 1;
        }
        let child = Child {
            language: language(),
            full: Span { start: 0, end: 3 },
            body: Span { start: 0, end: 3 },
        };
        Ok(Parsed {
            snapshot: source,
            state: if self.0 == "failed-children" {
                State::Failed
            } else if self.0 == "partial" {
                State::Partial
            } else {
                State::Complete
            },
            children: match self.0 {
                "cycle" | "failed-children" => vec![child],
                "siblings" => vec![child.clone(), child],
                _ => vec![],
            },
        })
    }
}
#[test]
fn rejects_stale_identity_overlapping_and_unbounded_provider_results() {
    let source = Snapshot::new("host", 1, "abc").unwrap();
    for mode in [
        "stale",
        "wrong-name",
        "cycle",
        "failed-children",
        "siblings",
    ] {
        let provider = Provider(mode);
        assert!(
            embedded::parse(
                &source,
                &language(),
                &HashMap::from([(language(), &provider as &dyn Grammar)]),
                3,
                16
            )
            .is_err(),
            "{mode}"
        );
    }
    assert!(embedded::parse(&source, &language(), &HashMap::new(), 0, 16).is_err());
}
#[test]
fn partial_unavailable_and_exact_registry_identity_are_retained() {
    let source = Snapshot::new("host", 1, "abc").unwrap();
    let partial = Provider("partial");
    assert_eq!(
        embedded::parse(
            &source,
            &language(),
            &HashMap::from([(language(), &partial as &dyn Grammar)]),
            3,
            16
        )
        .unwrap()
        .regions[0]
            .parse_state,
        State::Partial
    );
    let mut wrong_version = language();
    wrong_version.version = "2".into();
    assert_eq!(
        embedded::parse(
            &source,
            &language(),
            &HashMap::from([(wrong_version, &partial as &dyn Grammar)]),
            3,
            16
        )
        .unwrap()
        .regions[0]
            .parse_state,
        State::Unavailable
    );
    let complete = Provider("complete");
    assert_eq!(
        embedded::parse(
            &Snapshot::new("host", 1, "").unwrap(),
            &language(),
            &HashMap::from([(language(), &complete as &dyn Grammar)]),
            3,
            16
        )
        .unwrap()
        .regions[0]
            .parse_state,
        State::Complete
    );
}
