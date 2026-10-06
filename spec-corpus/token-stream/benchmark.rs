//! Observational benchmark, no timing threshold. Allocator is the existing audit crate.
mod generated;
use unlaxer_runtime::lexing::{Mode, Options};
use unlaxer_alloc_audit::{allocations, reset_peak_bytes, peak_bytes, CountingAllocator};
#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;
fn main() {
    let cases = [
        ("short", "a:b;".to_owned(), 100),
        ("dense", "a:b;".repeat(100), 10),
        ("comments", format!("alpha /*{}*/ : beta ;\n", " note ".repeat(64)).repeat(100), 5),
    ];
    for (name, source, iterations) in cases {
        for mode in [Mode::Direct, Mode::TriviaCache, Mode::TokensLazy, Mode::TokensEager] {
            for _ in 0..5 { assert!(generated::parser::parse_with_lexing(&source, Options { mode, preserve_trivia: false }).unwrap().succeeded); }
            let mut nanos = 0u128; let mut bytes = 0; let mut peak = 0; let mut metrics = None;
            for _ in 0..iterations {
                let baseline = reset_peak_bytes(); let before = allocations(); let start = std::time::Instant::now();
                let out = generated::parser::parse_with_lexing(&source, Options { mode, preserve_trivia: false }).unwrap();
                nanos += start.elapsed().as_nanos(); bytes += allocations().bytes - before.bytes;
                peak = peak.max(peak_bytes().saturating_sub(baseline));
                assert!(out.succeeded); metrics = Some(out.session.metrics());
            }
            let m = metrics.unwrap();
            println!("rust\t{name}\t{mode:?}\t{}\t{iterations}\t{}\t{}\t{}\t{}\t{}\t{peak}",source.chars().count(),nanos / iterations as u128,bytes / iterations,m.terminal_evaluations,m.trivia_evaluations,m.retained_entries);
        }
    }
}
