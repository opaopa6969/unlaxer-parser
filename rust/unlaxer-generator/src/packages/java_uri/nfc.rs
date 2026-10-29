//! NFC for the explicit HTTP encoder only. The tables follow JDK21 / Unicode 15;
//! see docs/ubnf-packages.md for JDK17's older combining-class data.
#[path = "nfc_data.rs"]
mod data;

fn class(c: u32) -> u8 {
    let i = data::CLASSES.partition_point(|&(_, end, _)| end < c);
    data::CLASSES
        .get(i)
        .filter(|&&(start, _, _)| start <= c)
        .map_or(0, |&(_, _, k)| k)
}

fn pair(a: u32, b: u32) -> Option<u32> {
    // UAX #15 algorithmic Hangul composition (L + V, LV + T).
    if (0x1100..0x1113).contains(&a) && (0x1161..0x1176).contains(&b) {
        return Some(0xac00 + (a - 0x1100) * 588 + (b - 0x1161) * 28);
    }
    if (0xac00..=0xd7a3).contains(&a) && (a - 0xac00) % 28 == 0 && (0x11a8..0x11c3).contains(&b) {
        return Some(a + b - 0x11a7);
    }
    data::PAIRS
        .binary_search_by_key(&(a, b), |&(first, second, _)| (first, second))
        .ok()
        .map(|i| data::PAIRS[i].2)
}

pub(super) fn normalize(source: &str) -> String {
    // Decompositions in the table are already recursive NFD. Keep the class beside
    // each scalar so stable sorting long combining runs does not become quadratic.
    let mut units = Vec::with_capacity(source.chars().count());
    for c in source.chars().map(u32::from) {
        if (0xac00..=0xd7a3).contains(&c) {
            let s = c - 0xac00;
            units.push((0x1100 + s / 588, 0));
            units.push((0x1161 + (s % 588) / 28, 0));
            if s % 28 != 0 {
                units.push((0x11a7 + s % 28, 0));
            }
        } else if let Ok(i) = data::DECOMPOSED.binary_search_by_key(&c, |&(cp, _)| cp) {
            units.extend(data::DECOMPOSED[i].1.chars().map(|c| {
                let c = u32::from(c);
                (c, class(c))
            }));
        } else {
            units.push((c, class(c)));
        }
    }
    let mut run = 0;
    for i in 0..=units.len() {
        if i == units.len() || units[i].1 == 0 {
            units[run..i].sort_by_key(|&(_, k)| k);
            run = i + 1;
        }
    }
    let mut composed = Vec::with_capacity(units.len());
    let mut starter = None;
    let mut previous = 0;
    for (c, k) in units {
        if let Some(i) = starter {
            if previous == 0 || previous < k {
                if let Some(cp) = pair(composed[i], c) {
                    composed[i] = cp;
                    continue;
                }
            }
        }
        if k == 0 {
            starter = Some(composed.len());
        }
        previous = k;
        composed.push(c);
    }
    composed
        .into_iter()
        .map(|cp| char::from_u32(cp).expect("NFC table contains only scalars"))
        .collect()
}
