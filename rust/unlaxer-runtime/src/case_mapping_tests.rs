#[test]
fn unicode_17_simple_mappings_match_every_scalar() {
    let mut upper: Vec<u32> = (0..=0x10ffff).collect();
    let mut lower = upper.clone();
    for row in
        include_str!("../../../spec-corpus/case-insensitive/unicode-17.0.0/UnicodeData.txt").lines()
    {
        let fields: Vec<_> = row.split(';').collect();
        let cp = usize::from_str_radix(fields[0], 16).unwrap();
        if !fields[12].is_empty() {
            upper[cp] = u32::from_str_radix(fields[12], 16).unwrap();
        }
        if !fields[13].is_empty() {
            lower[cp] = u32::from_str_radix(fields[13], 16).unwrap();
        }
    }
    for scalar in (0..=0x10ffff).filter_map(char::from_u32) {
        let expected = lower[upper[scalar as usize] as usize];
        let actual = super::unicode_case_data::key(scalar);
        assert_eq!(actual, expected, "U+{:04X}", scalar as u32);
        assert_eq!(
            actual,
            super::unicode_case_data::key(char::from_u32(actual).unwrap())
        );
    }
}
