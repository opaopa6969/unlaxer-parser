use unlaxer_runtime::lexical::{LexicalExpression, Op};

#[test]
fn tables_match_independent_unicode_property_boundaries() {
    let mut starts = vec![false; 0x110000];
    let mut continues = starts.clone();
    let mut boundaries = std::collections::BTreeSet::new();
    for line in include_str!("../../../spec-corpus/xid-identifier/unicode-17.0.0.txt").lines() {
        if line.starts_with('#') {
            continue;
        }
        let (range, property) = line.split_once(';').unwrap();
        let limits: Vec<_> = range.split("..").collect();
        let start = u32::from_str_radix(limits[0], 16).unwrap();
        let end = u32::from_str_radix(limits[limits.len() - 1], 16).unwrap();
        let expected = if property == "XID_Start" {
            &mut starts
        } else {
            &mut continues
        };
        expected[start as usize..=end as usize].fill(true);
        boundaries.extend([start.saturating_sub(1), start, end, end + 1]);
    }
    let expression = LexicalExpression {
        op: Op::XID_IDENTIFIER,
        text: "",
        min: 0,
        max: 0,
        children: vec![],
    };
    for codepoint in boundaries {
        let Some(character) = char::from_u32(codepoint) else {
            continue;
        };
        let scalar = character.to_string();
        assert_eq!(
            expression.match_at(&scalar, 0),
            starts[codepoint as usize].then_some(scalar.len()),
            "U+{codepoint:X} start"
        );
        assert_eq!(
            expression.match_at(&format!("A{scalar}"), 0),
            Some(if continues[codepoint as usize] {
                1 + scalar.len()
            } else {
                1
            }),
            "U+{codepoint:X} continue"
        );
    }
}
