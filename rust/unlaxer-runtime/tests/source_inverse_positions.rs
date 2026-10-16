use std::collections::HashMap;
use unlaxer_runtime::source::{Position, Snapshot};

#[test]
fn shared_scalar_boundaries_and_invalid_interiors() {
    for row in include_str!("../../../docs/fixtures/source-maps/inverse-positions.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let fields: Vec<_> = row.split('\t').collect();
        let source = Snapshot::new(
            fields[0],
            1,
            fields[1].replace("\\r", "\r").replace("\\n", "\n"),
        )
        .unwrap();
        let (mut utf16, mut utf8, mut lsp) = (HashMap::new(), HashMap::new(), HashMap::new());
        let mut max_line = 0;
        for boundary in fields[2].split(',') {
            let values: Vec<i64> = boundary
                .split(':')
                .map(|part| part.parse().unwrap())
                .collect();
            let (point, units, bytes) =
                (values[0] as usize, values[1] as usize, values[2] as usize);
            utf16.insert(units, point);
            utf8.insert(bytes, point);
            assert_eq!(source.utf16(point), Ok(units), "{}", fields[0]);
            assert_eq!(source.utf8(point), Ok(bytes), "{}", fields[0]);
            if values[3] < 0 {
                assert!(source.lsp(point).is_err(), "{}", fields[0]);
            } else {
                let position = Position {
                    line: values[3] as usize,
                    character: values[4] as usize,
                };
                lsp.insert((position.line, position.character), point);
                assert_eq!(source.lsp(point), Ok(position), "{}", fields[0]);
                max_line = max_line.max(position.line);
            }
        }
        let max_units = *utf16.keys().max().unwrap();
        let max_bytes = *utf8.keys().max().unwrap();
        for offset in 0..=max_units + 1 {
            assert_eq!(
                source.from_utf16(offset).ok(),
                utf16.get(&offset).copied(),
                "{} UTF16 {offset}",
                fields[0]
            );
        }
        for offset in 0..=max_bytes + 1 {
            assert_eq!(
                source.from_utf8(offset).ok(),
                utf8.get(&offset).copied(),
                "{} UTF8 {offset}",
                fields[0]
            );
        }
        for line in 0..=max_line + 1 {
            for character in 0..=max_units + 1 {
                assert_eq!(
                    source.from_lsp(Position { line, character }).ok(),
                    lsp.get(&(line, character)).copied(),
                    "{} LSP {line}:{character}",
                    fields[0]
                );
            }
        }
        assert!(source.from_utf8(usize::MAX).is_err());
        assert!(source.from_utf16(usize::MAX).is_err());
        assert!(source
            .from_lsp(Position {
                line: usize::MAX,
                character: usize::MAX
            })
            .is_err());
    }
}

#[test]
fn ordinary_document_end_and_invalid_offsets() {
    // Large enough to exercise the former prefix-rescan path without timing assertions.
    let source = Snapshot::new("large", 1, "日😀\r\n".repeat(20_000)).unwrap();
    assert_eq!(source.from_utf8(180_000), Ok(80_000));
    assert_eq!(source.from_utf16(100_000), Ok(80_000));
    assert_eq!(
        source.from_lsp(Position {
            line: 20_000,
            character: 0
        }),
        Ok(80_000)
    );
    assert!(source.from_utf8(179_996).is_err());
    assert!(source.from_utf16(99_997).is_err());
    assert!(source
        .from_lsp(Position {
            line: 20_000,
            character: 1
        })
        .is_err());
}
