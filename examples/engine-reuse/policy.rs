use std::collections::BTreeSet;
#[derive(Clone)]
struct Fact {
    purity: &'static str,
    nullable: bool,
    first: BTreeSet<String>,
    memo: &'static str,
}
fn fact(purity: &'static str, nullable: bool, first: &[&str], memo: &'static str) -> Fact {
    Fact {
        purity,
        nullable,
        first: first.iter().map(|s| s.to_string()).collect(),
        memo,
    }
}
fn parts(s: &str) -> Option<Vec<&str>> {
    let (mut start, mut depth, mut result) = (0, 0, Vec::new());
    for (i, c) in s.char_indices() {
        match c {
            '(' => depth += 1,
            ')' => {
                if depth == 0 {
                    return None;
                }
                depth -= 1
            }
            ';' if depth == 0 => {
                result.push(&s[start..i]);
                start = i + 1
            }
            _ => (),
        }
    }
    if depth != 0 {
        return None;
    }
    result.push(&s[start..]);
    Some(result)
}
fn proof(s: &str, depth: usize) -> Fact {
    if depth > 32 || s.len() > 4096 {
        return fact("UNKNOWN", true, &["*"], "LIMIT");
    }
    if let Some(lit) = s.strip_prefix("lit:") {
        return Fact {
            purity: "PURE",
            nullable: lit.is_empty(),
            first: lit.chars().take(1).map(|c| c.to_string()).collect(),
            memo: "SAFE",
        };
    }
    match s {
        "empty" => return fact("PURE", true, &[], "SAFE"),
        "one" => return fact("PURE", false, &["*"], "SAFE"),
        "cycle" => return fact("PURE", true, &["*"], "NULLABLE_CYCLE"),
        "variable" => return fact("PURE", false, &["*"], "VARIABLE_LEXING"),
        "custom" => return fact("UNKNOWN", true, &["*"], "UNKNOWN_CUSTOM"),
        "state" => return fact("STATEFUL", true, &["*"], "STATE_DEPENDENCE"),
        _ => (),
    }
    let unknown = || fact("UNKNOWN", true, &["*"], "UNKNOWN_PROOF");
    let Some(open) = s.find('(') else {
        return unknown();
    };
    if !s.ends_with(')') {
        return unknown();
    }
    let op = &s[..open];
    if !["seq", "alt", "star", "look"].contains(&op) {
        return unknown();
    }
    let Some(parts) = parts(&s[open + 1..s.len() - 1]) else {
        return unknown();
    };
    let children: Vec<_> = parts.iter().map(|s| proof(s, depth + 1)).collect();
    if ["star", "look"].contains(&op) && children.len() != 1 {
        return unknown();
    }
    let mut f = fact("PURE", op == "seq", &[], "SAFE");
    for c in &children {
        if c.purity != "PURE" {
            f.purity = c.purity
        }
        if f.memo == "SAFE" && c.memo != "SAFE" {
            f.memo = c.memo
        }
        if op == "alt" {
            if f.memo == "SAFE"
                && (c.nullable
                    || (!f.first.is_empty()
                        && (f.first.contains("*")
                            || c.first.contains("*")
                            || !f.first.is_disjoint(&c.first))))
            {
                f.memo = "OVERLAPPING_FIRST"
            }
            f.first.extend(c.first.iter().cloned());
            f.nullable |= c.nullable;
        } else if op == "seq" {
            if f.nullable {
                f.first.extend(c.first.iter().cloned())
            }
            f.nullable &= c.nullable;
        }
    }
    if op == "star" || op == "look" {
        f.first.extend(children[0].first.iter().cloned());
        f.nullable = true;
        if op == "look" {
            f.memo = "LOOKAHEAD_RESCAN"
        } else if f.memo == "SAFE" && children[0].nullable {
            f.memo = "NULLABLE_REPEAT"
        }
    }
    f
}
fn select(target: &str, requirements: &str, mode: &str, purity: &str) -> &'static str {
    let needs: Vec<_> = requirements.split(',').collect();
    let known = [
        "ast",
        "full-cst",
        "user-state",
        "scope-replay",
        "trace",
        "incremental",
        "custom-native",
        "custom-foreign",
        "provider",
        "callback",
    ];
    if !["java", "rust"].contains(&target)
        || !["auto", "classic", "ubnfc"].contains(&mode)
        || needs.iter().any(|s| !known.contains(s))
    {
        return "REJECT\tUNKNOWN_REQUEST";
    }
    if needs.contains(&"custom-foreign") {
        return "REJECT\tUNKNOWN_CUSTOM";
    }
    let rich = needs.iter().any(|s| *s != "ast") || purity != "PURE";
    if mode == "ubnfc" && rich {
        return "REJECT\tUBNFC_CAPABILITY_OR_REPLAY";
    }
    if target == "rust"
        && needs.iter().any(|s| {
            [
                "full-cst",
                "user-state",
                "scope-replay",
                "trace",
                "incremental",
            ]
            .contains(s)
        })
    {
        return "REJECT\tRUST_RICH_PROFILE_UNPROVEN";
    }
    if rich || mode == "classic" {
        "CLASSIC\tRICH_ONCE"
    } else {
        "UBNFC_MEMO\tFIXED_AST_ORACLE"
    }
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    for line in std::fs::read_to_string(&args[2])
        .unwrap()
        .lines()
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
    {
        let p: Vec<_> = line.split('\t').collect();
        if args[1] == "proof" {
            let f = proof(p[1], 0);
            println!("{}\t{}\t{}", p[0], f.purity, f.memo)
        } else {
            println!("{}\t{}", p[0], select(p[1], p[2], p[3], p[4]))
        }
    }
}
