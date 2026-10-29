//! Package-envelope compatibility with Gson, without changing raw artifact bytes.
use super::value::{Object, Value};

pub(super) fn parse(bytes: &[u8], artifact: bool) -> Result<Value, String> {
    // Gson ignores exactly one BOM, only at byte zero. Hashing/UTF-8/size checks
    // belong to the caller and must continue to see the original bytes.
    let bytes = bytes.strip_prefix(b"\xef\xbb\xbf").unwrap_or(bytes);
    if bytes
        .iter()
        .all(|b| matches!(b, b' ' | b'\t' | b'\r' | b'\n'))
    {
        return Err(super::error("expected object: document"));
    }
    // Gson's JsonObject adapter rejects even a JSON null (unlike an empty document).
    if bytes
        .iter()
        .find(|b| !matches!(b, b' ' | b'\t' | b'\r' | b'\n'))
        != Some(&b'{')
    {
        return Err(super::error("invalid JSON"));
    }
    if let Ok(value) = super::value::parse(bytes) {
        return Ok(value);
    }
    // Only unpaired UTF-16 escapes get a compatibility pass. The package reader
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
            let mut restored = Vec::with_capacity(fields.len());
            for (key, mut child) in std::mem::take(fields).into_entries() {
                let (key, surrogate) = text(&key);
                if surrogate && artifact && path.first().is_some_and(|p| p == "files") {
                    return Err(());
                }
                path.push(key.clone());
                restore(&mut child, path, artifact)?;
                path.pop();
                restored.push((key, child));
            }
            // Distinct surrogate escapes may decode to the same sentinel key.
            *fields = Object::from_entries(restored);
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
        let value = parse(br#"{"b\ud800":1,"a":2,"b\uD800":3}"#, false).unwrap();
        assert_eq!(
            value.as_object().unwrap().keys().collect::<Vec<_>>(),
            ["b\0", "a"]
        );
        assert_eq!(value["b\0"], serde_json::json!(3));
    }

    #[test]
    fn wide_objects_and_mixed_arrays_keep_order_lookup_and_sorted_output() {
        // Reverse-ordered wide object with duplicates, and scalar runs around nested objects.
        let mut text = String::from("{");
        for i in (0..3000).rev() {
            text.push_str(&format!("\"k{i:04}\":{i},"));
        }
        text.push_str(r#""k0005":"last","x":[1,2,{"b":1,"a":2},3,[{"d":1,"c":2}],4]}"#);
        let value = parse(text.as_bytes(), false).unwrap();
        let object = value.as_object().unwrap();
        assert_eq!(object.len(), 3001);
        let keys: Vec<_> = object.keys().cloned().collect();
        assert_eq!(keys[0], "k2999");
        assert_eq!(keys[2994], "k0005");
        assert_eq!(keys[2999], "k0000");
        assert_eq!(keys[3000], "x");
        for i in 0..3000 {
            assert!(object.contains_key(&format!("k{i:04}")));
        }
        assert!(object.get("k3000").is_none() && object.get("").is_none());
        assert_eq!(value["k0005"], "last");
        assert_eq!(value["k0006"], serde_json::json!(6));
        let x = |i: usize| match &value["x"] {
            Value::Array(items) => items[i].clone(),
            _ => unreachable!(),
        };
        assert_eq!(
            x(2).as_object().unwrap().keys().collect::<Vec<_>>(),
            ["b", "a"]
        );
        let Value::Array(inner) = x(4) else {
            unreachable!()
        };
        assert_eq!(
            inner[0].as_object().unwrap().keys().collect::<Vec<_>>(),
            ["d", "c"]
        );
        let dependencies = parse(
            br#"{"b":{"version":"1.0.0"},"a":{"version":"2.0.0"}}"#,
            false,
        )
        .unwrap();
        assert_eq!(
            super::super::output_references(&dependencies)
                .unwrap()
                .to_string(),
            r#"{"a":"2.0.0","b":"1.0.0"}"#
        );
        // The ordered tree must not grow each JSON value beyond serde_json's own node.
        assert!(std::mem::size_of::<Value>() <= std::mem::size_of::<serde_json::Value>());
    }

    #[test]
    fn document_kind_and_gson_depth_limit() {
        for bom in ["", "\u{feff}"] {
            for input in ["", " \r\n\t"] {
                assert_eq!(
                    parse(format!("{bom}{input}").as_bytes(), false).unwrap_err(),
                    super::super::error("expected object: document")
                );
            }
            for input in [
                "null",
                "[]",
                "1",
                "\"x\"",
                "true",
                "false",
                "\u{000b}",
                "\u{000c}",
                "{\"x\":1} true",
                "{\"b\":1,\"b\":}",
            ] {
                assert_eq!(
                    parse(format!("{bom}{input}").as_bytes(), false).unwrap_err(),
                    super::super::error("invalid JSON")
                );
            }
        }
        // Explicitly exercise the default Rust test-thread stack even if RUST_MIN_STACK is set.
        std::thread::Builder::new()
            .stack_size(2 * 1024 * 1024)
            .spawn(|| {
                for (open, close) in [("[", "]"), ("{\"x\":", "}"), ("[{\"x\":", "}]")] {
                    for depth in [126, 127, 128, 253, 254, 255, 256] {
                        for leaf in ["0", "\"\\ud800\"", "{\"b\":1,\"a\":2,\"b\":3}"] {
                            let text = format!(
                                "{{\"meta\":{}{leaf}{}}}",
                                open.repeat(depth),
                                close.repeat(depth)
                            );
                            let total =
                                1 + depth * close.len() + usize::from(leaf.starts_with('{'));
                            let result = parse(text.as_bytes(), false);
                            assert_eq!(result.is_ok(), total <= 255, "depth={total}: {result:?}");
                        }
                    }
                }
                let bytes = format!(
                    "{{\"meta\":{}0{}}}",
                    "[".repeat(4_194_299),
                    "]".repeat(4_194_299)
                );
                assert_eq!(bytes.len(), super::super::MAX_BYTES);
                assert_eq!(
                    parse(bytes.as_bytes(), false).unwrap_err(),
                    super::super::error("invalid JSON")
                );
            })
            .unwrap()
            .join()
            .unwrap();
    }

    #[test]
    fn numeric_tokens_and_reserved_keys_are_feature_independent() {
        // Identical oracle for standalone and workspace (DAP arbitrary_precision).
        for token in [
            "1",
            "1.0",
            "1e0",
            "1E+0",
            "-0",
            "1e999",
            "-1e999",
            "1e-999",
            "123456789012345678901234567890",
            "1234567890123456789012",
            "12345678901234567890123",
        ] {
            let value = parse(format!("{{\"schemaVersion\":{token}}}").as_bytes(), false).unwrap();
            let Value::Number(number) = &value["schemaVersion"] else {
                panic!("numeric token")
            };
            assert_eq!(number.as_str(), token);
            assert_eq!(super::super::versioned(&value).is_ok(), token == "1");
        }
        for depth in [0, 126, 253] {
            for leaf in [
                r#"{"$serde_json::private::Number":"1.5"}"#,
                r#"{"$serde_json::private::Number":null,"x":1}"#,
            ] {
                let raw = format!(
                    "{{\"meta\":{}{leaf}{}}}",
                    "[".repeat(depth),
                    "]".repeat(depth)
                );
                let value = parse(raw.as_bytes(), false).unwrap();
                let mut value = &value["meta"];
                for _ in 0..depth {
                    let Value::Array(items) = value else {
                        panic!("array")
                    };
                    value = &items[0];
                }
                assert!(value
                    .as_object()
                    .unwrap()
                    .contains_key("$serde_json::private::Number"));
            }
        }
        for length in [1023, 1024] {
            let text = format!("{{\"meta\":{}}}", "9".repeat(length));
            assert_eq!(parse(text.as_bytes(), false).is_ok(), length == 1023);
        }
        // Even an unused/overwritten token is fully validated before schema checks.
        for token in [
            "01", "-01", "+1", ".1", "1.", "1e", "1e+", "--1", "NaN", "Infinity", "1e999x",
        ] {
            let text = format!("{{\"schemaVersion\":2,\"meta\":{token},\"meta\":0}}");
            assert_eq!(
                parse(text.as_bytes(), false).unwrap_err(),
                super::super::error("invalid JSON"),
                "{token}"
            );
        }
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
