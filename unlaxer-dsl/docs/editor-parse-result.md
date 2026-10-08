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
