use unlaxer_ubnf::*;

#[path = "support/source_spans.rs"]
mod source_spans;

const SOURCE: &str = include_str!("fixtures/source/locations.ubnf");

#[test]
fn snapshot_preserves_every_shared_negative_diagnostic() {
    for line in include_str!("fixtures/negative.tsv").lines() {
        let (name, source) = line.split_once('\t').unwrap_or((line, ""));
        assert_eq!(
            parse(source).unwrap_err(),
            parse_with_source(source).unwrap_err(),
            "{name}"
        );
    }
}

#[test]
fn snapshot_owns_source_and_survives_later_failed_and_parallel_parses() {
    let snapshot = parse_with_source(SOURCE.to_owned()).unwrap();
    assert_eq!(*snapshot.ast(), parse(SOURCE).unwrap());
    assert_eq!(snapshot.source(), SOURCE);
    assert_eq!(snapshot.slice(snapshot.ast().span), Some(SOURCE));
    let threads: Vec<_> = (0..12)
        .map(|n| {
            std::thread::spawn(move || {
                let input = format!("// prefix {n}\n{SOURCE}");
                let other = parse_with_source(input.clone()).unwrap();
                assert_eq!(other.source(), input);
                assert_eq!(
                    other.slice(other.ast().grammars[0].imports[0].span),
                    Some("@import lib from '😀.ubnf'")
                );
            })
        })
        .collect();
    for thread in threads {
        thread.join().unwrap();
    }
    assert!(parse_with_source("invalid").is_err());
    assert_eq!(snapshot.slice(snapshot.ast().span), Some(SOURCE));
}

#[test]
fn offsets_slice_the_same_source_in_bytes_and_codepoints_with_lf_and_crlf() {
    for source in [SOURCE.to_owned(), SOURCE.replace('\n', "\r\n")] {
        let snapshot = parse_with_source(source).unwrap();
        for (path, span) in source_spans::file(&snapshot) {
            let text = snapshot.slice(span).unwrap();
            let cp_text: String = snapshot
                .source()
                .chars()
                .skip(span.codepoint_start)
                .take(span.codepoint_end - span.codepoint_start)
                .collect();
            assert_eq!(text, cp_text, "{path}");
            assert!(!text.is_empty(), "{path}");
        }
        let rule = &snapshot.ast().grammars[0].rules[0];
        let elems = &rule.body.alternatives[0].elements;
        assert_eq!(snapshot.slice(elems[0].element.span), Some("'😀'"));
        assert_eq!(snapshot.slice(elems[1].element.span), Some("'😀'"));
        assert_ne!(elems[0].element.span, elems[1].element.span);
        assert_eq!(
            snapshot.slice(elems[0].capture_span.unwrap()),
            Some("@first")
        );
        let last = elems.last().unwrap();
        assert_eq!(
            snapshot.slice(last.typeof_span.unwrap()),
            Some("@typeof(first)")
        );
        assert_eq!(snapshot.slice(last.capture_span.unwrap()), Some("@typed"));
        let repaired = &snapshot.ast().grammars[0].rules[1].body.alternatives[0].elements;
        assert_eq!(snapshot.slice(repaired[0].span), Some("'x'"));
        assert_eq!(
            snapshot.slice(repaired[1].span),
            Some("@typeof(first) T @next")
        );
        assert!(snapshot
            .slice(Span {
                byte_start: 4,
                byte_end: 5,
                ..Span::default()
            })
            .is_none());
    }
}
