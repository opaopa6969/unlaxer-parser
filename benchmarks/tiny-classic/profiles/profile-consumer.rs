use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, Ordering};
static ALLOCATED: AtomicU64 = AtomicU64::new(0);
struct CountedAllocator;
unsafe impl GlobalAlloc for CountedAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCATED.fetch_add(layout.size() as u64, Ordering::Relaxed);
        System.alloc(layout)
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        ALLOCATED.fetch_add(layout.size() as u64, Ordering::Relaxed);
        System.alloc_zeroed(layout)
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        System.dealloc(ptr, layout)
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        ALLOCATED.fetch_add(size as u64, Ordering::Relaxed);
        System.realloc(ptr, layout, size)
    }
}
#[global_allocator]
static ALLOCATOR: CountedAllocator = CountedAllocator;
mod generated;
use std::{env, fs, time::Instant};
use unlaxer_runtime::{json_string, Diagnostics, Memoization, ParseContext, ParseOptions};

fn operation(
    source: &str,
    memo: Memoization,
    diagnostics: Diagnostics,
    entry: &str,
) -> Result<String, String> {
    let allocation_begin = ALLOCATED.load(Ordering::Relaxed);
    let start = Instant::now();
    let mut context = ParseContext::with_options(
        source,
        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
    );
    let parsed = if entry == "Formula" {
        generated::parser::parse_context(&mut context)
    } else {
        let grammar = generated::parser::grammar();
        let root = grammar
            .iter()
            .position(|rule| rule.name == entry)
            .ok_or("unknown explicit entry")?;
        context.parse_shared_grammar(grammar, root, true)
    };
    let succeeded = parsed.is_ok();
    let accepted = succeeded && context.position() == source.chars().count();
    let ast = if accepted {
        let root = parsed
            .unwrap()
            .root_node()
            .ok_or("missing committed generated root")?;
        let tree = context.tree(root).ok_or("missing retained tree")?;
        Some(generated::mapper::map(&tree)?)
    } else {
        None
    };
    let elapsed = start.elapsed().as_nanos();
    let allocated = ALLOCATED.load(Ordering::Relaxed) - allocation_begin;
    let effective = context.options();
    let memo_effective = match effective.memoization {
        Memoization::Off => "OFF",
        Memoization::SafeFailures => "SAFE_FAILURES",
    };
    let diagnostics_effective = match effective.diagnostics {
        Diagnostics::Auto => "AUTO",
        Diagnostics::Detailed => "DETAILED",
        Diagnostics::DetailedOnFailure => "DETAILED_ON_FAILURE",
    };
    let syntax = context.failure();
    let expected = syntax
        .expected
        .iter()
        .map(|text| json_string(text))
        .collect::<Vec<_>>()
        .join(",");
    let semantic = context
        .scopes()
        .diagnostics()
        .iter()
        .map(|d| {
            format!(
                "{{\"message\":{},\"offset\":{},\"length\":{},\"severity\":{}}}",
                json_string(&d.message),
                d.offset,
                d.length,
                json_string(&format!("{:?}", d.severity).to_lowercase()),
            )
        })
        .collect::<Vec<_>>()
        .join(",");
    Ok(format!(
        "\"entry\":{},\"memo_effective\":{},\"diagnostics_effective\":{},\"accepted\":{accepted},\"parser_succeeded\":{succeeded},\"consumed_cp\":{},\"matched_cp\":{},\"parse_map_ns\":{elapsed},\"allocated_request_bytes\":{allocated},\"ast\":{},\"syntax\":{{\"offset\":{},\"expected\":[{expected}]}},\"semantic\":[{semantic}],\"failure_hits\":{},\"success_hits\":{}",
        json_string(entry),json_string(memo_effective),json_string(diagnostics_effective),context.position(),context.matched_position(),ast.map_or("null".into(),|value|value.canonical_json()),
        syntax.offset,context.memoized_failure_hits(),context.memoized_success_hits(),
    ))
}
fn main() {
    let args: Vec<_> = env::args().collect();
    let source = fs::read_to_string(&args[1]).unwrap();
    let memo = match args[2].as_str() {
        "OFF" => Memoization::Off,
        "SAFE_FAILURES" => Memoization::SafeFailures,
        _ => panic!("memo"),
    };
    let diagnostics = match args[3].as_str() {
        "AUTO" => Diagnostics::Auto,
        "DETAILED" => Diagnostics::Detailed,
        "DETAILED_ON_FAILURE" => Diagnostics::DetailedOnFailure,
        _ => panic!("diagnostics"),
    };
    let warmups: usize = args[4].parse().unwrap();
    let iterations: usize = args[5].parse().unwrap();
    for index in 0..1 + warmups + iterations {
        match operation(&source, memo, diagnostics, &args[6]) {
            Ok(row) => println!(
                "{{{row},\"phase\":{},\"iteration\":{index}}}",
                json_string(if index == 0 {
                    "cold"
                } else if index <= warmups {
                    "warmup"
                } else {
                    "warm"
                })
            ),
            Err(error) => {
                println!("{{\"error\":{}}}", json_string(&error));
                std::process::exit(2);
            }
        }
    }
}
