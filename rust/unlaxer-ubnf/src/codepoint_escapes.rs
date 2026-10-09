//! Scalar escapes for v2 character-class arguments, applied once to the raw quote.
pub(crate) fn decode(quoted: &str) -> Result<String, &'static str> {
    let mut input = quoted[1..quoted.len() - 1].chars().peekable();
    let mut result = String::new();
    while let Some(character) = input.next() {
        if character != '\\' {
            result.push(character);
            continue;
        }
        let Some(next) = input.next() else {
            result.push('\\');
            break;
        };
        if next != 'u' {
            match next {
                'n' => result.push('\n'),
                'r' => result.push('\r'),
                't' => result.push('\t'),
                '\\' => result.push('\\'),
                '\'' => result.push('\''),
                _ => {
                    result.push('\\');
                    result.push(next);
                }
            }
            continue;
        }
        let mut hex = String::new();
        if input.peek() == Some(&'{') {
            input.next();
            let mut closed = false;
            for value in input.by_ref() {
                if value == '}' {
                    closed = true;
                    break;
                }
                hex.push(value);
            }
            if !closed || hex.is_empty() || hex.chars().count() > 6 {
                return Err("E-TOKEN-ESCAPE-LENGTH: braced escape requires 1 to 6 hex digits");
            }
        } else {
            for _ in 0..4 {
                hex.push(
                    input
                        .next()
                        .ok_or("E-TOKEN-ESCAPE-LENGTH: escape requires exactly 4 hex digits")?,
                );
            }
        }
        if !hex.chars().all(|character| character.is_ascii_hexdigit()) {
            return Err("E-TOKEN-ESCAPE-HEX: escape requires ASCII hex digits");
        }
        let codepoint = u32::from_str_radix(&hex, 16).unwrap();
        if codepoint > 0x10ffff {
            return Err("E-TOKEN-ESCAPE-RANGE: code point exceeds U+10FFFF");
        }
        result.push(
            char::from_u32(codepoint)
                .ok_or("E-TOKEN-ESCAPE-SURROGATE: surrogate is not a Unicode scalar")?,
        );
    }
    Ok(result)
}

pub(crate) fn boundary(value: &str) -> Result<char, &'static str> {
    let mut chars = value.chars();
    match (chars.next(), chars.next()) {
        (Some(character), None) => Ok(character),
        _ => Err("E-TOKEN-RANGE-BOUNDARY: range boundary requires exactly one scalar"),
    }
}

pub(crate) fn validate_range(min: char, max: char) -> Result<(), &'static str> {
    if min > max {
        return Err("E-TOKEN-RANGE-ORDER: minimum exceeds maximum");
    }
    if (min as u32) <= 0xdfff && (max as u32) >= 0xd800 {
        return Err("E-TOKEN-RANGE-SURROGATE: range must not contain surrogates");
    }
    Ok(())
}
