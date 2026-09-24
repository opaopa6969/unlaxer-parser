//! 仕様由来オラクルを Rust frontend へ流す恒久テスト（Java の `UbnfSpecCorpusTest` と対）。
//!
//! 読むのは **Java 側とまったく同じ fixture**
//! (`unlaxer-dsl/src/test/resources/spec-corpus/ubnf/*.json`) で、期待値も同じ。
//! 原本は sibling repository `ubnfc` にあり、出所は同ディレクトリの `SOURCE.md` に書いてある。
//! 期待値は `unlaxer-dsl/specs/{ubnf-syntax,annotations,validation}.md` の規範文だけから
//! 導かれており、どの実装の観測値も使っていない。
//!
//! AGENTS.md「Java / Rust の機能対称性」に従い、片側だけの成功を完了根拠にしないための対。
//! 差は次の 1 点だけで、これは corpus の `scope` に明示されている:
//!
//! - `scope: validate` の 18 件は診断コードを見るケースで、`unlaxer-ubnf` は構文解析だけを行い
//!   独立した validator を持たない（Java 側は `GrammarValidator` で駆動する）。
//! - `scope: runtime` の 3 件は生成パーサの実行時挙動で、UBNF を読む処理系の入出力からは
//!   観測できない。Java 側も同じく駆動しない。

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

#[path = "support/canonical.rs"]
mod canonical;
#[path = "support/json.rs"]
mod json;

use json::Json;

/// 構文木の比較でしか判定できないケース。Java 側の `RUNTIME_SCOPE_IDS` と同じ集合。
const RUNTIME_SCOPE_IDS: [&str; 3] = [
    "char-range/inclusive-bounds",
    "error/hint-is-last-alternative",
    "separator/expansion",
];

/// 期待値を実装に合わせて書き換えず、黙って skip もしない差分。空であるべき状態が正常。
/// `(ケース ID, 実測の文言, 理由)`。
const DOCUMENTED_DEVIATIONS: [(&str, &str, &str); 0] = [];

fn repository_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn corpus_root() -> PathBuf {
    repository_root().join("unlaxer-dsl/src/test/resources/spec-corpus/ubnf")
}

struct Case {
    file: String,
    id: String,
    scope: String,
    input: String,
    verdict: String,
    code: Option<String>,
    question: Option<String>,
    canonical_contains: Vec<String>,
    same_canonical_as: Option<String>,
    citation_file: String,
    citation_heading: String,
    citation_sentence: String,
}

fn load() -> Vec<Case> {
    let root = corpus_root();
    let mut paths: Vec<PathBuf> = std::fs::read_dir(&root)
        .unwrap_or_else(|e| panic!("{}: {e}", root.display()))
        .filter_map(|entry| entry.ok().map(|entry| entry.path()))
        .filter(|path| path.extension().is_some_and(|e| e == "json"))
        .collect();
    paths.sort();
    assert!(
        paths.len() >= 8,
        "節ごとのファイルが少なすぎる: {}",
        paths.len()
    );

    let mut cases = Vec::new();
    for path in paths {
        let name = path.file_name().unwrap().to_string_lossy().into_owned();
        let text = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{name}: {e}"));
        let document = json::parse(&text).unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(
            document.required_text("family", &name),
            "ubnf",
            "{name}: family"
        );
        let entries = document
            .get("cases")
            .unwrap_or_else(|| panic!("{name}: cases が無い"))
            .array();
        assert!(!entries.is_empty(), "{name}: ケースが 0 件");
        for entry in entries {
            cases.push(to_case(&name, entry));
        }
    }
    cases
}

fn to_case(file: &str, entry: &Json) -> Case {
    let id = entry.required_text("id", file).to_owned();
    let expectation = entry
        .get("expectation")
        .unwrap_or_else(|| panic!("{id}: expectation が無い"));
    let citation = entry
        .get("citation")
        .unwrap_or_else(|| panic!("{id}: citation が無い"));
    Case {
        file: file.to_owned(),
        scope: entry.required_text("scope", &id).to_owned(),
        input: entry
            .get("input")
            .and_then(Json::text)
            .unwrap_or_default()
            .to_owned(),
        verdict: expectation.required_text("verdict", &id).to_owned(),
        code: expectation
            .get("code")
            .and_then(Json::text)
            .map(str::to_owned),
        question: expectation
            .get("question")
            .and_then(Json::text)
            .map(str::to_owned),
        canonical_contains: expectation
            .get("canonicalContains")
            .map(|value| {
                value
                    .array()
                    .iter()
                    .map(|v| v.text().unwrap_or_default().to_owned())
                    .collect()
            })
            .unwrap_or_default(),
        same_canonical_as: expectation
            .get("sameCanonicalAs")
            .and_then(Json::text)
            .map(str::to_owned),
        citation_file: citation.required_text("file", &id).to_owned(),
        citation_heading: citation.required_text("heading", &id).to_owned(),
        citation_sentence: citation.required_text("sentence", &id).to_owned(),
        id,
    }
}

/// 引用の照合に使う正規化。改行や連続空白の違いだけを吸収する。
fn normalize(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

#[test]
fn corpus_keeps_its_shape_and_does_not_guess_where_the_spec_is_silent() {
    let cases = load();
    assert!(cases.len() >= 100, "ケースが少なすぎる: {}", cases.len());
    let mut ids = BTreeSet::new();
    for case in &cases {
        assert!(ids.insert(case.id.clone()), "ケース ID の重複: {}", case.id);
        assert!(
            case.citation_heading.starts_with('#'),
            "{}: heading は見出し行そのものである必要がある",
            case.id
        );
        assert!(
            !case.citation_sentence.trim().is_empty(),
            "{}: 引用文が空",
            case.id
        );
        match case.verdict.as_str() {
            "diagnostic" => assert!(
                case.code.is_some(),
                "{}: diagnostic には code が要る",
                case.id
            ),
            "undetermined" => {
                assert!(
                    case.question.is_some(),
                    "{}: undetermined には question が要る",
                    case.id
                );
                assert_eq!(
                    case.scope, "runtime",
                    "{}: undetermined は runtime scope",
                    case.id
                );
            }
            "reject" => assert!(
                case.canonical_contains.is_empty() && case.same_canonical_as.is_none(),
                "{}: reject に構文木の期待は書けない",
                case.id
            ),
            _ => {}
        }
        if case.scope == "validate" {
            assert_eq!(
                case.verdict, "diagnostic",
                "{}: validate scope は diagnostic 期待であるべき",
                case.id
            );
        }
    }
    for case in &cases {
        if let Some(other) = &case.same_canonical_as {
            assert!(
                ids.contains(other),
                "{}: sameCanonicalAs の参照先が無い: {other}",
                case.id
            );
        }
    }
    // 仕様が沈黙している問いを推測で埋めないことが corpus の性質なので、
    // undetermined が 1 件も無い状態は書き方を誤った兆候として扱う。
    let undetermined: BTreeSet<&str> = cases
        .iter()
        .filter(|case| case.verdict == "undetermined")
        .map(|case| case.id.as_str())
        .collect();
    assert_eq!(
        undetermined,
        RUNTIME_SCOPE_IDS.into_iter().collect::<BTreeSet<_>>(),
        "undetermined の集合が SOURCE.md の説明と食い違う"
    );
}

/// 引用が実在すること。仕様書を編集したら sibling repository ではなく **この** テストが落ちる。
#[test]
fn every_citation_resolves_to_a_real_heading_and_sentence_in_the_spec() {
    let root = repository_root();
    let mut failures = Vec::new();
    let mut sources: Vec<(String, String)> = Vec::new();
    for case in load() {
        let text = match sources.iter().find(|(name, _)| *name == case.citation_file) {
            Some((_, text)) => text.clone(),
            None => {
                let path = root.join(&case.citation_file);
                let text = std::fs::read_to_string(&path)
                    .unwrap_or_else(|e| panic!("引用先の仕様書が読めない {}: {e}", path.display()));
                sources.push((case.citation_file.clone(), text.clone()));
                text
            }
        };
        if !text
            .lines()
            .filter(|line| line.starts_with('#'))
            .any(|line| line.trim() == case.citation_heading)
        {
            failures.push(format!(
                "{}: 見出しが {} に無い: {}",
                case.id, case.citation_file, case.citation_heading
            ));
        }
        if !normalize(&text).contains(&normalize(&case.citation_sentence)) {
            failures.push(format!(
                "{}: 引用文が {} に逐語で無い: {}",
                case.id, case.citation_file, case.citation_sentence
            ));
        }
    }
    assert!(
        failures.is_empty(),
        "引用が仕様書と食い違う（仕様を直したら corpus も直す。原本は ubnfc 側）:\n{}",
        failures.join("\n")
    );
}

#[test]
fn rust_frontend_agrees_with_every_spec_derived_expectation() {
    let cases = load();
    let mut canonical_by_id: Vec<(String, String)> = Vec::new();
    let mut failures: Vec<String> = Vec::new();
    let mut honoured: BTreeSet<&str> = BTreeSet::new();
    let mut driven = 0usize;
    let mut skipped_validate = 0usize;

    for case in &cases {
        if RUNTIME_SCOPE_IDS.contains(&case.id.as_str()) {
            continue;
        }
        if case.scope == "validate" {
            // unlaxer-ubnf は構文解析だけを行い、独立した validator を持たない。
            // 同じケースを Java 側の UbnfSpecCorpusTest が GrammarValidator で駆動する。
            skipped_validate += 1;
            continue;
        }
        driven += 1;
        let parsed = unlaxer_ubnf::parse(&case.input);
        let observation = match (&case.verdict[..], &parsed) {
            ("accept", Err(error)) => Some(format!("受理されるべきだが拒否された: {error}")),
            ("reject", Ok(_)) => Some("拒否されるべきだが受理された".to_owned()),
            ("accept" | "reject", _) => None,
            (other, _) => Some(format!("未知の verdict: {other}")),
        };
        if let Some(observation) = observation {
            record(case, &observation, &mut failures, &mut honoured);
        }
        if let Ok(file) = &parsed {
            canonical_by_id.push((case.id.clone(), canonical::file(file)));
        }
    }

    // 構文木の期待（accept のときだけ書ける）。
    for case in &cases {
        let Some((_, text)) = canonical_by_id.iter().find(|(id, _)| *id == case.id) else {
            continue;
        };
        for fragment in &case.canonical_contains {
            if !text.contains(fragment) {
                record(
                    case,
                    &format!("受理したが構造が違う。無い断片: {fragment}"),
                    &mut failures,
                    &mut honoured,
                );
            }
        }
        if let Some(other) = &case.same_canonical_as {
            match canonical_by_id.iter().find(|(id, _)| id == other) {
                None => record(
                    case,
                    &format!("比較先 {other} が受理されないので比較できない"),
                    &mut failures,
                    &mut honoured,
                ),
                Some((_, reference)) if reference != text => record(
                    case,
                    &format!("受理したが {other} と構文木が違う"),
                    &mut failures,
                    &mut honoured,
                ),
                Some(_) => {}
            }
        }
    }

    assert_eq!(
        driven + skipped_validate + RUNTIME_SCOPE_IDS.len(),
        cases.len(),
        "ケースの振り分けが合わない"
    );
    assert!(
        failures.is_empty(),
        "Rust frontend が仕様由来の期待と食い違う:\n{}",
        failures.join("\n")
    );
    assert_eq!(
        honoured,
        DOCUMENTED_DEVIATIONS
            .iter()
            .map(|(id, _, _)| *id)
            .collect::<BTreeSet<_>>(),
        "記録済みの差分が起きなくなった（直ったなら DOCUMENTED_DEVIATIONS から消す）"
    );
}

/// 期待と食い違った 1 件を、記録済みの差分なら `honoured` へ、そうでなければ失敗へ。
fn record(
    case: &Case,
    observation: &str,
    failures: &mut Vec<String>,
    honoured: &mut BTreeSet<&str>,
) {
    if let Some((id, _, _)) = DOCUMENTED_DEVIATIONS
        .iter()
        .find(|(id, expected, _)| *id == case.id && *expected == observation)
    {
        honoured.insert(id);
        return;
    }
    failures.push(format!(
        "{} [{}] 期待={} 入力={} 実測={} 引用={} {}",
        case.id,
        case.file,
        case.code.as_deref().unwrap_or(&case.verdict),
        case.input.replace('\n', "\\n"),
        observation,
        case.citation_file,
        case.citation_heading,
    ));
}
