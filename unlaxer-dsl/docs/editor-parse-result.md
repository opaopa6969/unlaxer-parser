# 編集途中の結果契約

`EditorParseResult<T>`（Java `org.unlaxer.dsl.semantic`、Rust `unlaxer_runtime::editor`）は
認識済みの構文結果と、同じ snapshot の `SemanticModel` を運ぶ immutable な契約である。
正常 AST の既存 API と回復 parser は変更しない。

| status | strict AST | missing / error node |
|---|---|---|
| COMPLETE | 必須。既存の正常値をそのまま格納 | なし |
| PARTIAL | なし | 1個以上 |
| FAILED | なし | 任意。保持できた sibling の意味情報も格納できる |

`strictAst()` / `strict_ast()` は COMPLETE のときだけ値を返す。回復が構文上成功していても、
欠損を通常の型付き AST に偽装しない。ノード・候補規則・call-site のリストは結果が所有する。

ノードの位置は半開の Unicode code point span。MISSING はゼロ幅、ERROR は非空で、
文書外の span は拒否する。候補規則は順序を保ち、空文字／重複を拒否する。
`utf16Span` / `utf16_span` は同じ境界を文書先頭からの UTF-16 offset に変換する。
CRLF は source の2 code pointをそのまま保持し、補助面文字は UTF-16 2単位となる。
LSP の line/character への変換は document/region adapter の役割とする。

region ID は任意の不透明な文字列 metadata（なし可、空文字不可）。この契約は領域構文や
source map を推測しない。`callAt(cursor, regionId)` / `call_at` は region 指定時に完全一致だけを
選び、なしのときは全 call-site を対象にする。

call-site は semantic call ID と0始まりの引数番号を参照する。引数の span は
SemanticModel が所有するので二重管理しない。未完成の識別子には実際の span と型 `?`、
空引数にはゼロ幅 span と型 `?` を adapter が渡す。候補 signature、正常引数、正常 sibling の
symbol は残す。`expectedTypesAt` / `expected_types_at` と `completeAt` / `complete_at` は既存の
型モデルを使い、例えば `process(context, ` の第2引数に対応する型と適合候補を得る。

cursor が slot の両端に一致する場合もその slot に含める。入れ子では最も短い slot を優先し、
同長は call ID の code point 順、引数番号の順で決める。completion は result version と違う
snapshot version を拒否し、保持 semantic model の URI / version / source も完全一致を要求する。

| コード | 拒否条件 |
|---|---|
| EDITOR_INVALID_DOCUMENT | 空 URI、負の version、表現可能な長さを超える文書 |
| EDITOR_INVALID_SOURCE | Java source が単独 surrogate を含む（Rust String では表現不可） |
| EDITOR_INVALID_STATUS | status / AST / defect list の矛盾 |
| EDITOR_SPAN_OUTSIDE_DOCUMENT | ノードまたは変換対象 span が source 外 |
| EDITOR_INVALID_NODE | missing の非ゼロ幅、error のゼロ幅 |
| EDITOR_INVALID_CANDIDATE | 空または重複の候補規則 |
| EDITOR_INVALID_REGION | 空 region ID |
| EDITOR_SNAPSHOT_MISMATCH | semantic model が別 snapshot |
| EDITOR_INVALID_CALL_SITE | model / call / 引数 slot が存在しない |
| EDITOR_DUPLICATE_CALL_SITE | 同じ call ID / 引数番号の重複 |
| EDITOR_INVALID_CURSOR | cursor が source 外 |
| EDITOR_STALE_SNAPSHOT | completion の snapshot version が古い |

SemanticModel が返す型・引数の診断コードはそのまま保持する。Java の負の cursor と単独
surrogate は host 固有の不正値、Rust の負の span/cursor は `usize` では表現できない。

## 検証と残る接続

`spec-corpus/editor-result/corpus.json` は未閉鎖括弧、途中 identifier、空引数、壊れた sibling、
未閉鎖 type、任意 region、正常／失敗、snapshot と node の不正条件を固定する。
Java/Rust は同じ source / adapter metadata を読み、status、strict AST、CP / UTF-16 span、
候補規則、引数番号、残る symbol、期待型、型による completion を独立期待値へ比較する。
この corpus は認識済み metadata の契約テストであり、文法からの抽出を済ませた証拠ではない。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test -Dtest=EditorParseResultConformanceTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml -p unlaxer-runtime --test editor_result
```

issue #403 はこの契約だけを扱う。親 #373 では生成 parser / mapper からの抽出、region adapter、
LSP / Playground の第2引数補完を接続する必要があり、この契約だけで完了とはしない。

## Generated CST and opt-in editor parsing

Generated Java mappers now expose `parseEditorCst(source, fragments, EditorCst.Options)`; Rust parser modules expose `parse_editor_cst(source, fragments, editor_cst::Options)`. These entries return an immutable/borrowed read-only inventory of selected rule nodes and their direct capture sites. Capture names are UBNF names rather than Java's internal binding IDs. Spans and capture text always refer to the original Unicode-scalar input.

A complete parse is attempted first. If it fails, editor parsing appends caller-selected EOF fragments in a bounded breadth-first search. Each retry gets a fresh detailed context; known failed non-prefix branches stop extending. Defaults are four fragments and 256 attempts, with maximums of eight fragments and 4096 attempts. Each fragment has at most 64 code points; the original source has at most 65536 code points. This is a best-effort editor service, not a guarantee that every incomplete grammar can be repaired. Non-EOF syntax, exhausted depth/attempt budgets, absent completion candidates and unsafe callbacks are observable as `SYNTAX`, `LIMIT`, `NO_COMPLETION` and `UNSAFE`. Callback retry requires the existing diagnostic-independence/replayability contract and explicit opt-in.

Inserted syntax is never a normal typed AST. The repaired tree stays private. A projected rule/capture crossing the original EOF has `synthetic=true`, its span is clipped to the original boundary, and its text is the original slice only. The defect inventory has zero-width `MISSING` at original EOF and preserves committed nonempty `ERROR` recovery spans. Candidate rules are ordered by containing span width and rule name. Recovery wrappers are normalized to the same rule inventory as Rust. A typed adapter must exclude synthetic/error-overlapping declarations and synthetic captures from healthy symbols and known types. Calls with original names may keep unknown argument slots even when their closing syntax is missing.

`examples/semantic-model/TypedModelEditor.java` and `editor_adapter.rs` demonstrate that policy using real generated CSTs. `EditorQueryProvider`/`editor_queries::EditorQueryProvider` preserve the immutable URI/version/source identity and PARTIAL state through `LanguageQueries`; they verify that a requested completion prefix is exactly original text before proposing an edit. Host forwarding requires a unique exact source map and keeps edits inside the current body. Inserted or transformed virtual text has no applicable source edit.

Generated LSP servers offer an optional `editorParseResult(uri,version,source)` hook. Their completion path validates the exact snapshot, translates UTF-16 line/character to code points, rejects surrogate-splitting positions, and marks typed completion data with state/version/expected types. The default hook supplies no model. `TypedModelEditorServer` is a concrete example.

Generated Playgrounds have an off-by-default editor checkbox and `pg_editor(cursorCodePoints)` ABI. They display original partial CST spans and preserve the ordinary strict parse result. The generated `src/editor_adapter.rs` is a replaceable semantic hook whose default makes no type claims. Copy the TypedModel `editor_adapter.rs` as `src/typed_adapter.rs` and `playground_editor_adapter.rs` as `src/editor_adapter.rs` to display actual typed completions. They are read-only displays; no synthetic value becomes an applicable edit. Regenerating with `--check` reports these intentional customizations, so keep them in source control and reapply them after regeneration.

See [the generated pipeline corpus](../../spec-corpus/editor-pipeline/README.md) for shared expectations, real LSP/WASM/browser verification and reproduction commands. Grammars remain responsible for recovery sites and model-specific interpretation; the strict AST API and the ordinary parsing defaults are unchanged.

Editor CST capture inventory is ordered by original start, end and capture name in both runtimes; coincident nested captures therefore have the same deterministic order.
