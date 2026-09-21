//! Evidence for unlaxer-parser#208: a ParseContext checkpoint is a stack value whose
//! payload handles are `Rc` clones, so opening, committing and rolling back checkpoints
//! performs no heap allocation. Each scenario compares two grammars that differ only in
//! the number of checkpoint layers wrapped around identical work; the allocation counts
//! must be equal, otherwise every extra layer would add allocations per iteration.
//!
//! The allocation counters are process-wide, so this binary opts out of the threaded
//! libtest harness (`harness = false`) and runs the scenarios one after another. The
//! workspace has no external crates, and the counting allocator lives in this crate.

use unlaxer_alloc_audit::{allocations, CountingAllocator};
use unlaxer_runtime::{Expr, ParseContext};

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

const ITERATIONS: usize = 512;
const EXTRA_LAYERS: usize = 8;

/// `Optional` returns its child's fragment unchanged, so each layer adds exactly one
/// checkpoint that commits without any other work.
fn committing_layers(layers: usize) -> Expr {
    let mut expression = Expr::literal("a");
    for _ in 0..layers {
        expression = expression.optional();
    }
    expression.repeat(ITERATIONS, Some(ITERATIONS))
}

/// A single-element `Sequence` propagates its child's failure, so each layer adds exactly
/// one checkpoint that rolls back. The failing literal records one diagnostic per
/// iteration regardless of the layer count.
fn rolling_back_layers(layers: usize) -> Expr {
    let mut failing = Expr::literal("q");
    for _ in 0..layers {
        failing = Expr::sequence([failing]);
    }
    failing
        .optional()
        .then(Expr::literal("a"))
        .repeat(ITERATIONS, Some(ITERATIONS))
}

fn allocations_during(input: &str, parser: &Expr, nonempty_payload: bool) -> usize {
    let mut context = ParseContext::new(input);
    if nonempty_payload {
        context.parse(&Expr::literal("x").capture("seed")).unwrap();
        context.set_state("seed", 1u32);
        context.scopes_mut().declare("seed", 0);
    }
    let before = allocations();
    context.parse(parser).unwrap();
    let after = allocations();
    assert_eq!(context.remaining(), "");
    after.allocations - before.allocations
}

fn check_layers_are_allocation_free(
    scenario: &str,
    build: fn(usize) -> Expr,
    nonempty_payload: bool,
) {
    let input = format!(
        "{}{}",
        if nonempty_payload { "x" } else { "" },
        "a".repeat(ITERATIONS)
    );
    let one_layer = allocations_during(&input, &build(1), nonempty_payload);
    let more_layers = allocations_during(&input, &build(1 + EXTRA_LAYERS), nonempty_payload);
    println!(
        "{scenario}: 1 layer = {one_layer} allocations, {} layers = {more_layers} allocations \
         ({} extra checkpoints)",
        1 + EXTRA_LAYERS,
        EXTRA_LAYERS * ITERATIONS
    );
    assert_eq!(
        one_layer,
        more_layers,
        "{scenario}: {} extra checkpoints must not allocate",
        EXTRA_LAYERS * ITERATIONS
    );
}

fn check_layers_open_checkpoints() {
    let input = "a".repeat(ITERATIONS);
    for layers in [1usize, 1 + EXTRA_LAYERS] {
        let mut context = ParseContext::new(&input);
        context.enable_checkpoint_metrics();
        context.parse(&committing_layers(layers)).unwrap();
        let metrics = context.snapshot_checkpoint_metrics();
        println!(
            "{layers} layers opened {} checkpoints, {} deep copies",
            metrics.opened, metrics.copy_on_write_deep_copies
        );
        assert!(
            metrics.opened >= (ITERATIONS * layers) as u64,
            "{layers} layers opened only {} checkpoints",
            metrics.opened
        );
        assert_eq!(metrics.copy_on_write_deep_copies, 0);
    }
}

fn check_capture_push_allocations() {
    let input = "a".repeat(ITERATIONS);
    let parser = Expr::literal("a").capture("part");
    let mut context = ParseContext::new(&input);
    let before = allocations();
    for _ in 0..ITERATIONS {
        context.parse(&parser).unwrap();
    }
    let count = allocations().allocations - before.allocations;
    assert_eq!(context.capture_spans("part").len(), ITERATIONS);
    assert_eq!(context.captured("part"), Some("a"));
    println!("capture pushes: {ITERATIONS} captures = {count} allocations");
    assert!(
        count <= 522,
        "512 captures with borrowed names must not exceed 522 allocations (owned names used 1034)"
    );
}

fn main() {
    check_capture_push_allocations();
    check_layers_open_checkpoints();
    check_layers_are_allocation_free("empty payload / commit", committing_layers, false);
    check_layers_are_allocation_free("nonempty payload / commit", committing_layers, true);
    check_layers_are_allocation_free("empty payload / rollback", rolling_back_layers, false);
    check_layers_are_allocation_free("nonempty payload / rollback", rolling_back_layers, true);
    println!("checkpoint_allocation: all scenarios passed");
}
