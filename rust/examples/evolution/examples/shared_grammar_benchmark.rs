use std::hint::black_box;
use std::sync::Arc;
use std::time::{Duration, Instant};

use unlaxer_evolution_example::generated::{mapper, parser};

const SOURCE: &str = "if(1, 3*3, neg(2))";

fn iterations() -> usize {
    std::env::var("UNLAXER_BENCH_ITERATIONS")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(10_000)
}

fn measure(iterations: usize, mut operation: impl FnMut()) -> Duration {
    for _ in 0..100 {
        operation();
    }
    let start = Instant::now();
    for _ in 0..iterations {
        operation();
    }
    start.elapsed()
}

fn report(name: &str, duration: Duration, iterations: usize) {
    println!(
        "{name}\t{:.1}\tns/op",
        duration.as_nanos() as f64 / iterations as f64
    );
}

fn main() {
    let iterations = iterations();
    let _ = parser::grammar();

    let legacy_setup = measure(iterations, || {
        black_box(parser::rules());
    });
    let shared_setup = measure(iterations, || {
        black_box(Arc::clone(parser::grammar()));
    });
    let legacy_parse = measure(iterations, || {
        let rules = parser::rules();
        black_box(unlaxer_runtime::parse_detailed(&rules, 0, true, SOURCE).unwrap());
    });
    let shared_parse = measure(iterations, || {
        black_box(parser::parse_tree_detailed(SOURCE).unwrap());
    });
    let legacy_parse_map = measure(iterations, || {
        let rules = parser::rules();
        let tree = unlaxer_runtime::parse_detailed(&rules, 0, true, SOURCE).unwrap();
        black_box(mapper::map(&tree).unwrap());
    });
    let shared_parse_map = measure(iterations, || {
        let tree = parser::parse_tree_detailed(SOURCE).unwrap();
        black_box(mapper::map(&tree).unwrap());
    });

    println!("mode\ttime\tunit");
    report("setup-only/legacy-clone", legacy_setup, iterations);
    report("setup-only/shared-arc", shared_setup, iterations);
    report("parse-only/legacy-clone", legacy_parse, iterations);
    report("parse-only/shared-arc", shared_parse, iterations);
    report("parse+map/legacy-clone", legacy_parse_map, iterations);
    report("parse+map/shared-arc", shared_parse_map, iterations);
}
