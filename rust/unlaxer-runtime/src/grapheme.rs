//! Unicode 17.0.0 extended grapheme coordinates, independent of scalar spans and display width.
use crate::source::{Result, Snapshot};
use crate::unicode_grapheme_data::{self as data, *};

pub const UNICODE_VERSION: &str = "17.0.0";

/// Numeric cluster index; interpret it only through the intended source's boundary table.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct GraphemeIndex(pub usize);
/// Number of extended grapheme clusters, not code points or rendered columns.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct GraphemeLength(pub usize);

#[derive(Debug, Clone)]
pub(crate) struct Boundaries {
    code_points: Vec<usize>,
    utf16: Vec<usize>,
    utf8: Vec<usize>,
}
impl Boundaries {
    pub(crate) fn new(text: &str) -> Self {
        let mut result = Self {
            code_points: vec![0],
            utf16: vec![0],
            utf8: vec![0],
        };
        let (mut units, mut points, mut previous, mut regional_count, mut conjunct) =
            (0, 0, GCB_OTHER, 0, INCB_NONE);
        let (mut pictographic_extend, mut pictographic_zwj) = (false, false);
        for (byte, scalar) in text.char_indices() {
            let property = data::gcb(scalar as u32);
            let indic = data::incb(scalar as u32);
            let pictographic = data::pictographic(scalar as u32);
            if points > 0
                && breaks(
                    previous,
                    property,
                    regional_count,
                    conjunct,
                    indic,
                    pictographic_zwj,
                    pictographic,
                )
            {
                result.code_points.push(points);
                result.utf16.push(units);
                result.utf8.push(byte);
            }
            pictographic_zwj = property == GCB_ZWJ && pictographic_extend;
            pictographic_extend = pictographic || (property == GCB_EXTEND && pictographic_extend);
            conjunct = match indic {
                INCB_CONSONANT => 1,
                INCB_LINKER if conjunct != 0 => 2,
                INCB_EXTEND => conjunct,
                _ => 0,
            };
            regional_count = if property == GCB_REGIONAL_INDICATOR {
                regional_count + 1
            } else {
                0
            };
            previous = property;
            units += scalar.len_utf16();
            points += 1;
        }
        if points != 0 {
            result.code_points.push(points);
            result.utf16.push(units);
            result.utf8.push(text.len());
        }
        result
    }
    pub(crate) fn next_utf8(&self, byte: usize) -> Option<usize> {
        let index = self.utf8.binary_search(&byte).ok()?;
        self.utf8.get(index + 1).copied()
    }
}

// UAX #29 revision 47, GB3–GB999, in normative rule order.
fn breaks(
    left: u8,
    right: u8,
    regional_count: usize,
    conjunct: u8,
    indic: u8,
    pictographic_zwj: bool,
    pictographic: bool,
) -> bool {
    if left == GCB_CR && right == GCB_LF {
        return false;
    } // GB3
    if matches!(left, GCB_CR | GCB_LF | GCB_CONTROL)
        || matches!(right, GCB_CR | GCB_LF | GCB_CONTROL)
    {
        return true;
    } // GB4/5
    if left == GCB_L && matches!(right, GCB_L | GCB_V | GCB_LV | GCB_LVT) {
        return false;
    } // GB6
    if matches!(left, GCB_LV | GCB_V) && matches!(right, GCB_V | GCB_T) {
        return false;
    } // GB7
    if matches!(left, GCB_LVT | GCB_T) && right == GCB_T {
        return false;
    } // GB8
    if matches!(right, GCB_EXTEND | GCB_ZWJ | GCB_SPACINGMARK) || left == GCB_PREPEND {
        return false;
    } // GB9/9a/9b
    if conjunct == 2 && indic == INCB_CONSONANT {
        return false;
    } // GB9c
    if pictographic_zwj && pictographic {
        return false;
    } // GB11
    left != GCB_REGIONAL_INDICATOR || right != GCB_REGIONAL_INDICATOR || regional_count % 2 == 0
    // GB12/13, GB999
}

/// Immutable boundaries bound to an owned snapshot. Original text is never normalized.
/// Building the table takes O(n) time and O(g) space; conversions take O(log g).
#[derive(Debug, Clone)]
pub struct GraphemeBoundaryTable {
    snapshot: Snapshot,
    boundaries: Boundaries,
}
impl GraphemeBoundaryTable {
    pub fn new(snapshot: Snapshot) -> Self {
        let boundaries = Boundaries::new(&snapshot.text);
        Self {
            snapshot,
            boundaries,
        }
    }
    pub fn snapshot(&self) -> &Snapshot {
        &self.snapshot
    }
    pub fn require_snapshot(&self, source: &Snapshot) -> Result<()> {
        if source == &self.snapshot {
            Ok(())
        } else {
            Err("stale grapheme boundary table")
        }
    }
    pub fn length(&self) -> GraphemeLength {
        GraphemeLength(self.boundaries.code_points.len() - 1)
    }
    pub fn code_point(&self, index: GraphemeIndex) -> Result<usize> {
        at(&self.boundaries.code_points, index)
    }
    pub fn utf16(&self, index: GraphemeIndex) -> Result<usize> {
        at(&self.boundaries.utf16, index)
    }
    pub fn utf8(&self, index: GraphemeIndex) -> Result<usize> {
        at(&self.boundaries.utf8, index)
    }
    pub fn from_code_point(&self, offset: usize) -> Result<GraphemeIndex> {
        strict(&self.boundaries.code_points, offset)
    }
    pub fn from_utf16(&self, offset: usize) -> Result<GraphemeIndex> {
        strict(&self.boundaries.utf16, offset)
    }
    pub fn from_utf8(&self, offset: usize) -> Result<GraphemeIndex> {
        strict(&self.boundaries.utf8, offset)
    }
    /// Greatest cluster boundary at or before the supplied scalar offset.
    pub fn floor_code_point(&self, offset: usize) -> Result<GraphemeIndex> {
        rounded(&self.boundaries.code_points, offset, false)
    }
    /// Least cluster boundary at or after the supplied scalar offset.
    pub fn ceiling_code_point(&self, offset: usize) -> Result<GraphemeIndex> {
        rounded(&self.boundaries.code_points, offset, true)
    }
    pub fn floor_utf16(&self, offset: usize) -> Result<GraphemeIndex> {
        rounded(&self.boundaries.utf16, offset, false)
    }
    pub fn ceiling_utf16(&self, offset: usize) -> Result<GraphemeIndex> {
        rounded(&self.boundaries.utf16, offset, true)
    }
    pub fn floor_utf8(&self, offset: usize) -> Result<GraphemeIndex> {
        rounded(&self.boundaries.utf8, offset, false)
    }
    pub fn ceiling_utf8(&self, offset: usize) -> Result<GraphemeIndex> {
        rounded(&self.boundaries.utf8, offset, true)
    }
}
fn at(offsets: &[usize], index: GraphemeIndex) -> Result<usize> {
    offsets
        .get(index.0)
        .copied()
        .ok_or("outside grapheme table")
}
fn strict(offsets: &[usize], offset: usize) -> Result<GraphemeIndex> {
    offsets
        .binary_search(&offset)
        .map(GraphemeIndex)
        .map_err(|_| "not a grapheme boundary")
}
fn rounded(offsets: &[usize], offset: usize, forward: bool) -> Result<GraphemeIndex> {
    if offset > offsets[offsets.len() - 1] {
        return Err("outside grapheme table");
    }
    Ok(GraphemeIndex(match offsets.binary_search(&offset) {
        Ok(index) => index,
        Err(index) => {
            if forward {
                index
            } else {
                index - 1
            }
        }
    }))
}

/// Consume exactly one full cluster from an original-source boundary; all spans remain CP.
pub struct GraphemeParser;
impl crate::Parser for GraphemeParser {
    fn parse(&self, context: &mut crate::ParseContext<'_>) -> crate::ParseResult {
        context.parse(&crate::Expr::Grapheme)
    }
}
