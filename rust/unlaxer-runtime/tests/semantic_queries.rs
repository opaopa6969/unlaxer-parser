use std::collections::BTreeMap;
use std::sync::{Arc, Barrier};
use unlaxer_runtime::semantic_query_cache::*;
struct Provider {
    cancellation: Cancellation,
    barrier: Option<Arc<Barrier>>,
}
impl Executor for Provider {
    fn execute(&self, key: &Key, context: &mut dyn Context) -> Result<String, Failure> {
        match key.kind.as_str() {
            "read" => Ok(context.input("a")?.map_or("missing".into(), |i| i.value)),
            "pair" => {
                context.input("a")?;
                context.input("b")?;
                Ok("value".into())
            }
            "cancel" => {
                context.query(Key::new("read", "a", "")?)?;
                let barrier = self.barrier.as_ref().unwrap();
                barrier.wait();
                barrier.wait();
                assert!(self.cancellation.is_cancelled());
                Ok("cancelled result".into())
            }
            "chain" => {
                let depth = key.identity.parse::<usize>().unwrap();
                if depth == 0 {
                    Ok("done".into())
                } else {
                    context.query(Key::new("chain", &(depth - 1).to_string(), "")?)
                }
            }
            _ => Err(Failure::Unsupported),
        }
    }
}
fn limits() -> Limits {
    Limits {
        entries: 4,
        bytes: 1024,
        inputs: 2,
        steps: 256,
        dependencies: 8,
    }
}
fn database(limits: Limits, provider: Arc<dyn Executor>) -> SemanticQueries {
    SemanticQueries::new(
        "p",
        limits,
        ["read", "pair", "cancel", "chain"]
            .into_iter()
            .map(|kind| (kind.into(), provider.clone()))
            .collect(),
    )
    .unwrap()
}
fn inputs() -> BTreeMap<String, Input> {
    [(
        "a".into(),
        Input {
            uri: "file:a".into(),
            version: 1,
            value: "😀".into(),
        },
    )]
    .into_iter()
    .collect()
}
#[test]
fn cancellation_from_another_thread_discards_staged_children() {
    let cancellation = Cancellation::default();
    let barrier = Arc::new(Barrier::new(2));
    let mut db = database(
        limits(),
        Arc::new(Provider {
            cancellation: cancellation.clone(),
            barrier: Some(barrier.clone()),
        }),
    );
    db.update(1, inputs()).unwrap();
    let signal = cancellation.clone();
    let thread = std::thread::spawn(move || {
        barrier.wait();
        signal.cancel();
        barrier.wait();
    });
    assert!(matches!(
        db.evaluate(Key::new("cancel", "a", "").unwrap(), cancellation),
        Err(Failure::Cancelled)
    ));
    thread.join().unwrap();
    assert_eq!(db.stats().entries, 0);
    assert_eq!(db.stats().evaluated, 0);
    let result = db
        .evaluate(Key::new("read", "a", "").unwrap(), Cancellation::default())
        .unwrap();
    assert_eq!(db.accept(&result).unwrap(), "😀");
    assert_eq!(db.stats().evaluated, 1);
}
#[test]
fn budgets_zero_capacity_and_default_stack_are_bounded() {
    let provider = Arc::new(Provider {
        cancellation: Cancellation::default(),
        barrier: None,
    });
    for (configuration, key, failure) in [
        (
            Limits {
                dependencies: 1,
                ..limits()
            },
            Key::new("pair", "a", "").unwrap(),
            Failure::Limit,
        ),
        (
            Limits {
                steps: 1,
                ..limits()
            },
            Key::new("chain", "1", "").unwrap(),
            Failure::Limit,
        ),
        (
            limits(),
            Key::new("chain", "150", "").unwrap(),
            Failure::Limit,
        ),
    ] {
        let mut db = database(configuration, provider.clone());
        db.update(1, inputs()).unwrap();
        assert!(matches!(db.evaluate(key, Cancellation::default()), Err(e) if e == failure));
        assert_eq!(db.stats().entries, 0);
    }
    for configuration in [
        Limits {
            entries: 0,
            ..limits()
        },
        Limits {
            bytes: 1,
            ..limits()
        },
    ] {
        let mut db = database(configuration, provider.clone());
        db.update(1, inputs()).unwrap();
        for _ in 0..2 {
            assert_eq!(
                db.evaluate(Key::new("read", "a", "").unwrap(), Cancellation::default())
                    .unwrap()
                    .value(),
                "😀"
            );
        }
        assert_eq!(db.stats().evaluated, 2);
        assert_eq!(db.stats().entries, 0);
        assert_eq!(db.stats().bytes, 0);
    }
}
#[test]
fn rejected_updates_are_atomic_and_close_releases_provider() {
    let provider = Arc::new(Provider {
        cancellation: Cancellation::default(),
        barrier: None,
    });
    let weak = Arc::downgrade(&provider);
    let mut db = database(limits(), provider.clone());
    drop(provider);
    db.update(1, inputs()).unwrap();
    let result = db
        .evaluate(Key::new("read", "a", "").unwrap(), Cancellation::default())
        .unwrap();
    let mut changed = inputs();
    changed.get_mut("a").unwrap().value = "changed".into();
    assert_eq!(db.update(2, changed), Err(Failure::Stale));
    assert_eq!(db.accept(&result).unwrap(), "😀");
    let mut many = inputs();
    for key in ["b", "c"] {
        many.insert(
            key.into(),
            Input {
                uri: key.into(),
                version: 1,
                value: String::new(),
            },
        );
    }
    assert_eq!(db.update(2, many), Err(Failure::Limit));
    assert!(db.is_current(&result));
    db.close();
    assert!(weak.upgrade().is_none());
    assert_eq!(db.stats().entries, 0);
    assert_eq!(db.retained_inputs(), 0);
}
