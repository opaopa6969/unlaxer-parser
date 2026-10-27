# Classic の実 terminal trace と token DAP

[#463](https://github.com/opaopa6969/unlaxer-parser/issues/463) は、Java の実 `Token.filteredChildren` の leaf と、Rust の実認識中に記録する leaf metadata を接続する。CST の rule/capture node を token と見なしたり、解析後に原文を再解析して leaf を作ったりしない。通常の CST・AST・評価器・構文認識は変更しない。

```rust
use unlaxer_runtime::{ParseOptions, token_trace::Limits};
let options = ParseOptions::default().with_token_trace(Limits::default());
let tree = generated::parser::parse_tree_with_options(source, options)?;
let leaves = tree.token_trace().expect("explicitly enabled")?;
```

`ParseContext::token_trace()` / `Tree::token_trace()` は未指定なら `None`。指定時は完全な committed trace または `Error` を返し、不完全な prefix を完全な trace として返さない。`Tree` は source と metadata を所有し、後続操作・rollback・context破棄から独立する。既存 CST arena へ trace 用 node を追加しない。trace は既存内部 metadata store に置き、通常の `Tree` の Debug 表示も維持する。`ParseOptions` は新しい opt-in field を持つ。

各 `Leaf` は label、4種の `TokenKind`、`TokenSource`、その leaf が認識された直後の consumed/matched CP cursor を保持する。`span()` は原文CPの半開区間、`text(original_source)` は原文または独立した generated text を返す。virtual と source の由来は独立で、入力由来の virtual consumed は通常の input span を持つ。先に match-only で `a` を読んだ入力 `ab` で virtual consumed の `b` を読む場合、source `[1,2)` に対し両 cursor は1となる。

| 実認識経路 | trace の保持 |
|---|---|
| literal / ANY / EOF | 実 atom の source と cursor。EOF は原文末尾のゼロ幅 leaf |
| Number | sign・digit・point・exponent の実分岐。空 Optional と失敗した指数から戻った Optional も Java と同じ境界 |
| Identifier | 先頭・後続文字の実ループ。後続なしの ZeroOrMore はゼロ幅 |
| single/double quoted | 区切り・通常文字・backslash・escape対象の実ループ。空 body の ZeroOrMore も保持 |
| `TokenExpr::Word/Sequence/Choice/Invert/StopInvert/StopConsume` | 4種と consume/invert の既存伝播を保持 |
| ordered / longest / unique-longest / predictive choice | 敗者の leaf・上限エラーを rollback。選択した勝者の metadata を保持 |
| PEG lookahead / UBNF lookahead | 投機的 leaf を外側 consumed trace へ残さない。明示 TokenExpr の match-only token とは区別 |
| Java-compatible optional/repeat | 実際に空で成功した Optional / ZeroOrMore の leaf を保持 |
| CHAR_RANGE / NEGATION / declarative lexical / token stream・lexical context の literal（`''` を含む） | 原子的認識の span/text を保持（`''` は Java `LiteralParser("")` と同じ幅0 leaf、[#713](empty-literal.md)。非空 literal も Java `Lexing.LiteralParser`、[#768](empty-literal.md)）。Rust の表示labelは `SingleCharacterParser` / `LexicalTokenParser`。Java の生成 subclass 名との表示差を残す |
| code fence / UNTIL / EMPTY / backreference / recovery | この trace では `Unsupported`。通常parseとAST利用は従来どおり |

Number の `12.3` は `Optional[0,0]`, `Digit[0,1]`, `Digit[1,2]`, `Point[2,3]`, `Digit[3,4]`, `Optional[4,4]`。これは既存 number の分岐内で記録する。`1e+` の失敗した exponent は消え、`Optional[1,1]` を保持する。元の number の受理や診断を変更しない。

## 上限・custom parser・memo

上限は保持 leaf 65,536件、leaf が参照する source の累積UTF-8長4MiB。caller は小さくできるが上限を超えて拡大できない。ゼロ幅 leaf も件数に含む。generated text は容量を確認してから metadata へ複製する。上限超過は trace の `Error::Limit` とし、parser の受理/拒否を変更しない。破棄された候補での上限超過は候補とともに戻し、選択された候補の超過は保持する。DAP はこのエラーを表示して停止せず終了する。

件数と bytes は、最終 trace に残る leaf だけを数える（[#866](https://github.com/opaopa6969/unlaxer-parser/issues/866)）。Classic の collector（Chain・Choice・Optional・反復・Rule・MatchOnly・NotPropagatableSource・CaptureBoundary など）は、自分と同じ kind の直接の子だけを残し、自分の node もその kind になる。認識中の collector の kind と leaf の kind が食い違えば、その leaf は collector の完了時に必ず除かれる。例は consumed 親の下の match-only token、virtual 親の下の `stop_consume` 終端、`Not` の幅0 leaf。trace はこの scope に expression の checkpoint と同時に入り、rollback と collector の完了で戻る。除かれると決まった leaf は記録せず、予算にも数えず、generated text も複製しない。残る leaf が予算を超える場合は従来どおり `Error::Limit` とする。受理・両 cursor・CST・capture・state は予算に依存しない。scope は既存の checkpoint の trace 有効時の分岐だけで出入りし、件数・bytes を hard ceiling 内の `u32` に収めて checkpoint の大きさを変えない。

AST value node（`TextValue` / `ValueBoundary`）は子の source kind を引き継ぎ、子を絞り込まない。custom の結果は先頭 node の kind を source kind にするので、value node の直下（間に collector が無い）の callback は、kind の違う node を外側の collector に通しうる。そのため、外側の collector はその callback の leaf の要否を決めない。callback の中で呼んだ collector は引き続き判定する。この位置の callback が後で除かれる leaf を記録した場合は、最終 trace が予算内でも `Error::Limit` になりうる（不完全な trace は返さない）。これは Rust 側の保守的な境界である。Java には trace 予算 API が無いので、Java の実 leaf と cursor を保持 trace の基準とし、予算の境界は Rust で検証する。

`Expr::Custom` / `CustomWith` は成功時に `declare_complete_token_trace()` を明示しない限り `Unsupported` となる。一部の通常 Expr を呼んだだけでは対応と見なさない。宣言は「この callback の全認識が trace 対応 Expr / TokenExpr / generated token 登録で記録され、raw cursor 移動を隠さない」という実装者の契約である。返す node は、その callback 自身の認識で得たものに限る。入れ子 callback は個別に宣言する。誤った宣言を自動証明するAPIではない。未知 adapter を黙って trace 対応と見なさない。

既存 failure memo は trace の成功結果をキャッシュしない。失敗時の trace rollback 後に memo を保存し、hit で失敗候補の leaf を復活させない。共通fixtureで OFF の hit=0 / ON の実 hit=1 を確認する。Java の leaf cursor 観測は observer を付けると memo が無効になるため、memoを使う2回の実行と、独立 callback で各 leaf cursor を測る3回目を分ける。Rust の観測 accessor はその context の failure memo hit 数のみを返す。

## DAP

生成 `dap::backend()` は `ParserBackend` を返す。`runtimeMode: "token"`（既定）または `steppingMode: "token"` で生成 parser の trace を使い、`"ast"` は既存 strict mapper を使う。`AstBackend` と既存 custom Backend は引き続きAST専用。custom Backend が token に対応する場合は `supports_token_trace()` を明示する。

token mode では consumed / virtual consumed の input-backed leaf を巡回する。`Current Token` scope、`Token` variable、`runtimeMode`、`steppingMode` を返し、AST件数は0。位置変換・breakpoint・next・continue・source offset・client基底は [AST DAP と共通](classic-rust-dap.md)。これは解析後の構造 inspection であり、live evaluator の実行停止ではない。

`TokenSource::Generated { anchor, text }` は runtime trace で保持できるが、原文 slice を表示する現在の Rust DAP は consumed generated source を明示拒否する。generated text を空の原文 anchor として表示しない。Java の既存 collector/任意 Token 表示 API は変更しない。この host 境界と上記未対応複合 atom は、全仕様の [#111](https://github.com/opaopa6969/unlaxer-parser/issues/111) に残る。

## 独立期待値と実行

- [`leaves.tsv`](../conformance/token-trace/leaves.tsv): 40入力。Java実Token / Rust実traceで source・kind・CP span・text・最終両cursor・各leaf両cursorを照合する。再実行、4種、consume/invert、sequence/choice/longest/predictive/lookahead、Number/Identifier/quoted、空要素、失敗rollback、memo hit を含む。
- [`generated.tsv`](../conformance/token-trace/generated.tsv): generated virtual の anchor/text/kind/cursor を3入力で独立比較。
- [`budget.tsv`](../conformance/token-trace/budget.tsv)（#866）: 23入力。Java 実 Token の leaf・両 cursor・各 leaf の cursor を基準に、Rust の十分な予算の trace を照合する。4種・invert・reset、CRLF・非BMP、MatchOnly・stopper・choice 敗者・`Not`/先読み・外側失敗・複合 Number、異なる kind の collector の入れ子、root の MatchOnly を含む。Rust はさらに保持件数・UTF-8 bytes の n-1/n/n+1、Rule・`TextValue`・`ValueBoundary` 経由、memo Off/SafeFailures で、受理・cursor・CST が予算に依存しないことを確かめる。Rust 単体テストは custom callback・generated text・context の再利用、DAP protocol テストは真の Limit/Unsupported で step を表示しないことを確かめる。
- [`classic-token-dap`](fixtures/classic-token-dap/): 8入力、0/1基底、source offset 0/7、実Java/Rust stdio DAP。UTF-16、CRLF、EOF、breakpoint/next/continue、variables、無効request、失敗relaunchを確認。両host生成6ファイルのbyte一致も検証。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
 -Dtest=TokenTraceConformanceTest,ClassicTokenDapProtocolConformanceTest,ClassicDapProtocolConformanceTest,RustUbnfFrontendConformanceTest,PlaygroundCommandTest \
 -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml --workspace
```

CI は `target/token-trace.tsv` と `target/classic-token-dap-protocol.tsv` を必須にし、`token-trace.tsv` に budget.tsv の全行分の Java と Rust の行があることも確かめる。通常 Maven test だけで Rust 比較済みとは扱わない。性能freeze・root-retry最適化・一般positive assertionの既存差は変更しない。
