//! Process-wide heap allocation counting for allocation evidence tests.
//!
//! Install [`CountingAllocator`] as the `#[global_allocator]` of a test binary and read the
//! counters with [`allocations`]. This is the only place in the workspace that implements an
//! unsafe trait; the implementation forwards every call to [`System`] unchanged.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};

static ALLOCATIONS: AtomicUsize = AtomicUsize::new(0);
static BYTES: AtomicUsize = AtomicUsize::new(0);

/// Counts `alloc` and `realloc` calls (and the bytes they request) before delegating to
/// the system allocator.
pub struct CountingAllocator;

#[allow(unsafe_code)]
unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        BYTES.fetch_add(layout.size(), Ordering::Relaxed);
        System.alloc(layout)
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        System.dealloc(ptr, layout)
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        BYTES.fetch_add(new_size, Ordering::Relaxed);
        System.realloc(ptr, layout, new_size)
    }
}

/// Allocation calls and requested bytes since process start.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Counts {
    pub allocations: usize,
    pub bytes: usize,
}

/// Snapshot of the process-wide counters. Meaningful only while no other thread allocates.
pub fn allocations() -> Counts {
    Counts {
        allocations: ALLOCATIONS.load(Ordering::Relaxed),
        bytes: BYTES.load(Ordering::Relaxed),
    }
}
