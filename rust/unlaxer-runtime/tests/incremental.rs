use unlaxer_runtime::incremental::{IncrementalParseCache, Revisions, SnapshotParseCache};
use unlaxer_runtime::source::Snapshot;
fn revisions() -> Revisions {
    Revisions {
        grammar: "g".into(),
        profile: "p".into(),
        state: "s".into(),
    }
}
fn snapshot(uri: &str, version: u64, source: &str) -> Snapshot {
    Snapshot::new(uri, version, source).unwrap()
}
#[test]
fn ownership_failure_and_lru_bounds() {
    let mut cache = SnapshotParseCache::new(2, 4);
    let first = snapshot("a", 1, "😀");
    let rev = revisions();
    let mut value = vec!["old".to_owned()];
    cache.put(&first, &rev, &value);
    value.clear();
    cache.get(&first, &rev).unwrap().clear();
    assert_eq!(cache.get(&first, &rev).unwrap(), ["old"]);
    cache.put(&snapshot("b", 1, "ab"), &rev, &vec!["b".into()]);
    assert_eq!(cache.stats().source_utf16_units, 4);
    cache.get(&first, &rev);
    cache.put(&snapshot("c", 1, "x"), &rev, &vec!["c".into()]);
    assert!(cache.get(&snapshot("b", 1, "ab"), &rev).is_none());
    assert!(cache
        .get_or_try_compute(&snapshot("d", 1, "y"), &rev, || Err("failed"))
        .is_err());
    assert_eq!(cache.stats().entries, 2);
    assert_eq!(cache.get(&first, &rev).unwrap(), ["old"]);
    cache.put(&snapshot("large", 1, "12345"), &rev, &vec!["large".into()]);
    assert_eq!(cache.stats().entries, 2);
    cache.invalidate("a");
    assert_eq!(cache.stats().entries, 1);
    cache.invalidate_all();
    assert_eq!(cache.stats().entries, 0);
    assert!(cache.stats().hits > 0);
    cache.clear();
    assert_eq!(cache.stats().hits, 0);
    assert_eq!(cache.stats().misses, 0);
}
#[test]
fn every_identity_component_and_parallel_snapshots() {
    let mut cache = SnapshotParseCache::new(8, 100);
    let rev = revisions();
    let original = snapshot("a", 1, "x");
    cache.put(&original, &rev, &"original".to_owned());
    for changed in [
        snapshot("b", 1, "x"),
        snapshot("a", 2, "x"),
        snapshot("a", 1, "y"),
    ] {
        assert!(cache.get(&changed, &rev).is_none());
    }
    for changed in [
        Revisions {
            grammar: "g2".into(),
            ..rev.clone()
        },
        Revisions {
            profile: "p2".into(),
            ..rev.clone()
        },
        Revisions {
            state: "s2".into(),
            ..rev.clone()
        },
    ] {
        assert!(cache.get(&original, &changed).is_none());
    }
    let cache = std::sync::Arc::new(std::sync::Mutex::new(cache));
    let old = cache.clone();
    let new = cache.clone();
    let a = std::thread::spawn(move || {
        old.lock()
            .unwrap()
            .get(&snapshot("a", 1, "x"), &revisions())
            .unwrap()
    });
    let b = std::thread::spawn(move || {
        new.lock()
            .unwrap()
            .get_or_try_compute(&snapshot("a", 2, "y"), &revisions(), || {
                Ok::<_, ()>("new".to_owned())
            })
            .unwrap()
    });
    assert_eq!(a.join().unwrap(), "original");
    assert_eq!(b.join().unwrap(), "new");
    assert_eq!(
        cache.lock().unwrap().get(&original, &rev).unwrap(),
        "original"
    );
}
#[test]
fn chunks_boundaries_and_disabled_retention() {
    let mut cache = IncrementalParseCache::<String>::new(2, 4);
    cache.put("a", None, 0);
    cache.put("😀", None, 0);
    cache.get_cached("a");
    cache.put("b", None, 0);
    assert!(cache.is_cached("a"));
    assert!(!cache.is_cached("😀"));
    assert!(cache.is_cached("b"));
    cache.put("oversize", None, 0);
    assert_eq!(cache.size(), 2);
    assert_eq!(
        IncrementalParseCache::<String>::split_into_chunks("a;'b;c';\"d\\\";e\";😀", &[";"]),
        ["a;", "'b;c';", "\"d\\\";e\";", "😀"]
    );
    let mut disabled = SnapshotParseCache::new(0, 0);
    assert_eq!(
        disabled
            .get_or_try_compute(&snapshot("a", 1, "x"), &revisions(), || Ok::<_, ()>("ok"))
            .unwrap(),
        "ok"
    );
    assert_eq!(disabled.stats().entries, 0);
}

#[test]
fn shared_quote_aware_chunk_oracle() {
    for line in include_str!("../../../spec-corpus/incremental-cache/chunks.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let f: Vec<_> = line.split('\t').collect();
        let decode = |s: &str| s.replace("\\r", "\r").replace("\\n", "\n");
        let source = if f[2] == "~" {
            String::new()
        } else {
            decode(f[2])
        };
        let delimiters: Vec<String> = if f[1] == "~" {
            vec![]
        } else {
            f[1].chars().map(|c| c.to_string()).collect()
        };
        let delimiters: Vec<&str> = delimiters.iter().map(String::as_str).collect();
        let expected: Vec<String> = if f[3] == "~" {
            vec![]
        } else {
            f[3].split('|').map(decode).collect()
        };
        assert_eq!(
            IncrementalParseCache::<String>::split_into_chunks(&source, &delimiters),
            expected,
            "{}",
            f[0]
        );
    }
}

#[test]
fn invalid_coordinate_keeps_previous_snapshot() {
    let mut cache = SnapshotParseCache::new(4, 100);
    let old = snapshot("a", 1, "😀\r\nx");
    let rev = revisions();
    cache.put(&old, &rev, &"last");
    assert!(old.from_utf16(1).is_err());
    assert!(old
        .from_lsp(unlaxer_runtime::source::Position {
            line: 0,
            character: 3
        })
        .is_err());
    assert_eq!(cache.get(&old, &rev), Some("last"));
    assert_eq!(cache.stats().entries, 1);
}
