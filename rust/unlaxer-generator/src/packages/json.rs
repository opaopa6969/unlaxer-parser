//! Package-envelope compatibility with Gson, without changing raw artifact bytes.
use super::value::{Object, Value};

pub(super) fn parse(bytes: &[u8], artifact: bool) -> Result<Value, String> {
    // Gson ignores exactly one BOM, only at byte zero. Hashing/UTF-8/size checks
    // belong to the caller and must continue to see the original bytes.
    let bytes = if let Some(rest) = bytes.strip_prefix(b"\xef\xbb\xbf") {
        if rest
            .iter()
            .all(|b| matches!(b, b' ' | b'\t' | b'\r' | b'\n'))
        {
            return Err(super::error("expected object: document"));
        }
        rest
    } else {
        bytes
    };
    if let Ok(value) = super::value::parse(bytes) {
        return Ok(value);
    }
    // Only unpaired UTF-16 escapes get a compatibility pass. serde_json still
    // validates the entire JSON (including trailing input) before any field use.
    let invalid = || super::error("invalid JSON");
    let rewritten = rewrite(bytes).ok_or_else(invalid)?;
    let mut value = super::value::parse(&rewritten).map_err(|_| invalid())?;
    restore(&mut value, &mut Vec::new(), artifact).map_err(|_| invalid())?;
    Ok(value)
}

fn unit(bytes: &[u8], start: usize) -> Option<u16> {
    let escape = bytes.get(start..start + 6)?;
    if &escape[..2] != b"\\u" {
        return None;
    }
    escape[2..].iter().try_fold(0, |value, b| {
        char::from(*b)
            .to_digit(16)
            .map(|hex| value * 16 + hex as u16)
    })
}

// This scans string escapes, not JSON structure. NUL is quoted as NUL+'0', so
// real user strings cannot forge the NUL+'s'+hex surrogate markers. Valid pairs,
// escaped backslashes/quotes, invalid escapes and all non-string bytes stay intact.
fn rewrite(bytes: &[u8]) -> Option<Vec<u8>> {
    let mut output = Vec::with_capacity(bytes.len());
    let (mut i, mut quoted, mut changed) = (0, false, false);
    while i < bytes.len() {
        if quoted && bytes[i] == b'\\' {
            if let Some(value) = unit(bytes, i) {
                if (0xd800..=0xdbff).contains(&value)
                    && unit(bytes, i + 6).is_some_and(|low| (0xdc00..=0xdfff).contains(&low))
                {
                    output.extend_from_slice(&bytes[i..i + 12]);
                    i += 12;
                    continue;
                }
                if (0xd800..=0xdfff).contains(&value) {
                    output.extend_from_slice(format!("\\u0000s{value:04x}").as_bytes());
                    changed = true;
                    i += 6;
                    continue;
                }
                if value == 0 {
                    output.extend_from_slice(b"\\u00000");
                    i += 6;
                    continue;
                }
            }
            let end = (i + 2).min(bytes.len());
            output.extend_from_slice(&bytes[i..end]);
            i = end;
            continue;
        }
        if bytes[i] == b'"' {
            quoted = !quoted;
        }
        output.push(bytes[i]);
        i += 1;
    }
    changed.then_some(output)
}

fn text(value: &str) -> (String, bool) {
    let mut output = String::with_capacity(value.len());
    let mut surrogate = false;
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        output.push(c);
        if c == '\0' && chars.next() == Some('s') {
            // Keep an invalid-path/ASCII-validation sentinel, never a Unicode
            // replacement character that might name a real file or package.
            surrogate = true;
            for _ in 0..4 {
                chars.next();
            }
        }
    }
    (output, surrogate)
}

fn restore(value: &mut Value, path: &mut Vec<String>, artifact: bool) -> Result<(), ()> {
    match value {
        Value::String(s) => {
            let (decoded, surrogate) = text(s);
            if surrogate
                && ((artifact
                    && matches!(path.first().map(String::as_str), Some("entry" | "files")))
                    || (path.len() == 3
                        && path[0] == "dependencies"
                        && path[2] == "source"
                        && decoded.starts_with("https:")))
            {
                // These fields can expose the actual UTF-16 text. Keep the old
                // JSON rejection until they have a lossless cross-host contract.
                return Err(());
            }
            *s = decoded;
        }
        Value::Object(fields) => {
            let mut restored = Object::default();
            for (key, mut child) in std::mem::take(fields).into_entries() {
                let (key, surrogate) = text(&key);
                if surrogate && artifact && path.first().is_some_and(|p| p == "files") {
                    return Err(());
                }
                path.push(key.clone());
                restore(&mut child, path, artifact)?;
                path.pop();
                restored.insert(key, child);
            }
            *fields = restored;
        }
        Value::Array(items) => {
            for child in items {
                path.push(String::new());
                restore(child, path, artifact)?;
                path.pop();
            }
        }
        _ => {}
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn objects_keep_first_position_last_value_and_order_independent_equality() {
        let value = parse(
            br#"{"b":{"a":2,"z":1,"z":3},"a":0,"\u0062":{"z":4,"a":5}}"#,
            false,
        )
        .unwrap();
        assert_eq!(
            value.as_object().unwrap().keys().collect::<Vec<_>>(),
            ["b", "a"]
        );
        assert_eq!(
            value["b"].as_object().unwrap().keys().collect::<Vec<_>>(),
            ["z", "a"]
        );
        assert_eq!(value["b"]["z"], serde_json::json!(4));
        let reordered = parse(br#"{"a":0,"b":{"a":5,"z":4}}"#, false).unwrap();
        assert_eq!(value, reordered);
        assert_eq!(
            serde_json::to_string(&value).unwrap(),
            r#"{"a":0,"b":{"a":5,"z":4}}"#
        );
        let value = parse(br#"{"b\ud800":1,"a":2,"b\uD800":3}"#, false).unwrap();
        assert_eq!(
            value.as_object().unwrap().keys().collect::<Vec<_>>(),
            ["b\0", "a"]
        );
        assert_eq!(value["b\0"], serde_json::json!(3));
    }

    #[test]
    fn document_acceptance_outside_member_order_is_unchanged() {
        for input in [
            b"".as_slice(),
            b" \r\n",
            b"{\"x\":1} true",
            b"{\"b\":1,\"b\":}",
        ] {
            assert_eq!(
                parse(input, false).unwrap_err(),
                super::super::error("invalid JSON")
            );
        }
        for input in [b"null".as_slice(), b"[]", b"1", b"\"x\"", b"true"] {
            assert!(parse(input, false).unwrap().as_object().is_none());
        }
        let nested = |depth| format!("{{\"meta\":{}0{}}}", "[".repeat(depth), "]".repeat(depth));
        assert!(parse(nested(126).as_bytes(), false).is_ok());
        assert_eq!(
            parse(nested(127).as_bytes(), false).unwrap_err(),
            super::super::error("invalid JSON")
        );
    }

    #[test]
    fn numeric_values_keep_serde_semantics_with_workspace_feature_unification() {
        // Run both standalone and with the workspace (DAP enables arbitrary_precision).
        // Numeric map envelopes must not become package objects or change diagnostics.
        for input in [
            r#"{"dependencies":1.25,"files":18446744073709551616,"schemaVersion":1e0}"#,
            r#"{"note":[-0.0,0.5,1e300,18446744073709551616]}"#,
            r#"{"note":1e999}"#,
            r#"{"note":{"$serde_json::private::Number":"1.5"}}"#,
            r#"{"note":{"$serde_json::private::Number":null}}"#,
        ] {
            let reference = serde_json::from_str::<serde_json::Value>(input);
            let ordered = parse(input.as_bytes(), false);
            match reference {
                Ok(reference) => assert_eq!(ordered.unwrap(), reference, "{input}"),
                Err(_) => assert_eq!(ordered.unwrap_err(), super::super::error("invalid JSON")),
            }
        }
        let value = parse(br#"{"dependencies":1.25,"files":1.25}"#, false).unwrap();
        assert_eq!(
            super::super::dependencies(&value["dependencies"]).unwrap_err(),
            super::super::error("expected object: dependencies")
        );
        assert!(value["files"].as_object().is_none());
    }

    #[test]
    fn bom_is_single_initial_and_keeps_empty_document_diagnostic() {
        assert_eq!(
            parse(b"\xef\xbb\xbf \r\n{}", false).unwrap(),
            serde_json::json!({})
        );
        for bytes in [b"\xef\xbb\xbf".as_slice(), b"\xef\xbb\xbf \n"] {
            assert_eq!(
                parse(bytes, false).unwrap_err(),
                super::super::error("expected object: document")
            );
        }
        for bytes in [
            b"\xef\xbb\xbf\xef\xbb\xbf{}".as_slice(),
            b" \xef\xbb\xbf{}",
            b"\xef\xbb\xbf{",
        ] {
            assert_eq!(
                parse(bytes, false).unwrap_err(),
                super::super::error("invalid JSON")
            );
        }
        assert_eq!(
            parse(br#"{"note":"\ufeff"}"#, false).unwrap()["note"],
            "\u{feff}"
        );
    }

    #[test]
    fn only_unpaired_escapes_are_marked_and_user_markers_cannot_collide() {
        let value = parse(br#"{"a":"\ud800\udc00","b":"\ud800x\udc00","c":"\\ud800","d":"\u0000sD800\"\udfff","e":"\u00000","f":"\uD800"}"#, false).unwrap();
        assert_eq!(value["a"], "\u{10000}");
        assert_eq!(value["b"], "\0x\0");
        assert_eq!(value["c"], "\\ud800");
        assert_eq!(value["d"], "\0sD800\"\0");
        assert_eq!(value["e"], "\0\u{30}");
        assert_eq!(value["f"], "\0");
    }

    #[test]
    fn surrogates_outside_strings_stay_invalid_and_keys_are_restored() {
        for bytes in [
            br#"{"a":1}\ud800"#.as_slice(),
            br#"{"a":\ud800}"#,
            br#"{\ud800:1}"#,
        ] {
            assert_eq!(
                parse(bytes, false).unwrap_err(),
                super::super::error("invalid JSON")
            );
        }
        let value = parse(br#"{"k\ud800":"\ud800A","p":"\ud800\\udc00"}"#, false).unwrap();
        assert_eq!(value["k\0"], "\0A");
        assert_eq!(value["p"], "\0\\udc00");
    }

    #[test]
    fn full_json_validation_and_last_member_precede_field_use() {
        for bytes in [
            br#"{"source":"local:\ud800",}"#.as_slice(),
            br#"{"x":"\ud800"} true"#,
            br#"{"x":"\ud800\uZZZZ"}"#,
            br#"{"x":"\ud800\q"}"#,
        ] {
            assert_eq!(
                parse(bytes, false).unwrap_err(),
                super::super::error("invalid JSON")
            );
        }
        assert_eq!(
            parse(
                br#"{"source":"local:\ud800","source":"local:ok.json"}"#,
                false
            )
            .unwrap()["source"],
            "local:ok.json"
        );
        assert_eq!(
            parse(br#"{"files":{"ok":"\ud800"},"files":{}}"#, true).unwrap()["files"],
            serde_json::json!({})
        );
        // A real NUL in source text is preserved, even in a document with a lone surrogate elsewhere.
        assert_eq!(
            parse(br#"{"note":"\ud800","files":{"x":"\u0000sD800"}}"#, true).unwrap()["files"]["x"],
            "\0sD800"
        );
    }

    #[test]
    fn unresolved_source_text_and_https_are_not_lossily_accepted() {
        for bytes in [
            br#"{"entry":"\ud800.ubnf"}"#.as_slice(),
            br#"{"files":{"\ud800.ubnf":"text"}}"#,
            br#"{"files":{"ok.ubnf":"\udc00"}}"#,
            br#"{"dependencies":{"a":{"source":"https://host/\ud800"}}}"#,
        ] {
            assert_eq!(
                parse(bytes, true).unwrap_err(),
                super::super::error("invalid JSON")
            );
        }
        assert!(parse(br#"{"unknown":{"files":{"name":"\ud800"}}}"#, true).is_ok());
    }
}
