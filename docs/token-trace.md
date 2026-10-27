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

`Expr::Custom` / `CustomWith` は成功時に `declare_complete_token_trace()` を明示しない限り `Unsupported` となる。一部の通常 Expr を呼んだだけでは対応と見なさない。宣言は「この callback の全認識が trace 対応 Expr / TokenExpr / generated token 登録で記録され、raw cursor 移動を隠さない」という実装者の契約である。入れ子 callback は個別に宣言する。誤った宣言を自動証明するAPIではない。未知 adapter を黙って trace 対応と見なさない。

既存 failure memo は trace の成功結果をキャッシュしない。失敗時の trace rollback 後に memo を保存し、hit で失敗候補の leaf を復活させない。共通fixtureで OFF の hit=0 / ON の実 hit=1 を確認する。Java の leaf cursor 観測は observer を付けると memo が無効になるため、memoを使う2回の実行と、独立 callback で各 leaf cursor を測る3回目を分ける。Rust の観測 accessor はその context の failure memo hit 数のみを返す。

## DAP

生成 `dap::backend()` は `ParserBackend` を返す。`runtimeMode: "token"`（既定）または `steppingMode: "token"` で生成 parser の trace を使い、`"ast"` は既存 strict mapper を使う。`AstBackend` と既存 custom Backend は引き続きAST専用。custom Backend が token に対応する場合は `supports_token_trace()` を明示する。

token mode では consumed / virtual consumed の input-backed / generated leaf を committed trace の順で巡回する。同じ anchor の leaf も統合・ソートせず、それぞれ停止する。`Current Token` scope、`Token` variable、`runtimeMode`、`steppingMode` を返し、AST件数は0。位置変換・breakpoint・next・continue・source offset・client基底は [AST DAP と共通](classic-rust-dap.md)。これは解析後の構造 inspection であり、live evaluator の実行停止ではない。

### generated token の本文と原文 anchor（#863）

`TokenSource::Input(span)` の本文は原文 slice。`TokenSource::Generated { anchor, text }` の本文は独立した `text` であり、空文字も有効。原文の空 slice で代用しない。`anchor` は原文上の CP 位置で、DAP の stack frame は `[anchor, anchor)` を原文 snapshot から UTF-16 へ変換する。generated text の長さ・改行・非 BMP 文字は原文の開始/終了位置を動かさない。source は launch した program、breakpoint は同じ anchor の原文行を使う。CRLF の途中など原文 snapshot で表現できない位置は従来どおり拒否する。

Rust の公開経路は、`ParseOptions::with_token_trace` を有効にした `ParseContext` で `add_token_node(..., TokenKind::VirtualConsumed, TokenSource::Generated { anchor, text })` を呼び、成功した完全な `Tree` を `unlaxer_dap::token_steps(&tree)` へ渡す。`Backend::supports_token_trace()` を `true` にし、`Backend::steps` からこの結果を返す。[実行可能な custom backend](../rust/unlaxer-dap/examples/generated_token_dap.rs)を参照。custom callback 内なら `declare_complete_token_trace()` も必要で、宣言漏れを無視して成功にしない。match-only leaf、choice 敗者、先読み・外側失敗の leaf は停止対象にならない。

`Step.generated_text` は `None` なら input-backed、`Some(String)` なら generated。`token_steps` は trace が所有する本文を複製するので Tree/context 破棄後も表示できる。generated の非ゼロ幅 span と AST mode への generated text は拒否する。`TokenSource::Projected` の DAP 表示は今回の対象ではなく、従来どおり明示拒否する。trace limit（件数・UTF-8 bytes）、未指定・不完全 trace、parse の resource failure は正常な空 trace に置換せず、stderr・terminated・exited で終了する。

Java の既存生成 DAP は `token.source.sourceAsString().strip()` を variables に表示する。一方、素の detached source の offset は0、長さは生成本文の CP 長であり、`PortableTokenMetadata.registerGenerated(token, anchor)` の位置は自動では使われない。共通 `ascii-mixed` fixture で、原文 `ab`、anchor 1、生成本文 `R` の既定表示が本文 `"R"`・原文 CP `[0,1)` になることを、変更前の実 stdio で確認した。

既存の任意 Token 表示と collector を維持し、生成 Java DAP に `protected int[] tokenSourceSpan(Token token)` を追加した。既定は従来の source offset/長さを返す。generated source の所有者は、成功した token に対応する `PortableTokenMetadata.Info` の `[offset(), end()]` をこの hook で返す。stackTrace と breakpoint の両方がこの範囲を使い、variables は既存の本文経路を使う。[共通 fixture の Java consumer](fixtures/generated-token-dap/JavaMain.java.txt)は実 custom collector・detached Token・metadata を使ってこの接続を実行する。原文と generated text を同じ source に捏造しない。表示上の strip と引用符 escape は両版で同じで、保持している raw text は変更しない。

開発版の移行: 手組みの Rust `Step { label, span }` には input/AST 用の `generated_text: None` を追加する。`--dap` 生成物は Java host / native host とも再生成する。Java の hook を使う consumer も DAP を再生成する。公開済み 3.3.0 と parser のホットパスは変更しない。

## 独立期待値と実行

- [`leaves.tsv`](../conformance/token-trace/leaves.tsv): 40入力。Java実Token / Rust実traceで source・kind・CP span・text・最終両cursor・各leaf両cursorを照合する。再実行、4種、consume/invert、sequence/choice/longest/predictive/lookahead、Number/Identifier/quoted、空要素、失敗rollback、memo hit を含む。
- [`generated.tsv`](../conformance/token-trace/generated.tsv): generated virtual の anchor/text/kind/cursor を3入力で独立比較。
- [`classic-token-dap`](fixtures/classic-token-dap/): 8入力、0/1基底、source offset 0/7、実Java/Rust stdio DAP。UTF-16、CRLF、EOF、breakpoint/next/continue、variables、無効request、失敗relaunchを確認。両host生成6ファイルのbyte一致も検証。
- [`generated-token-dap/cases.json`](fixtures/generated-token-dap/cases.json): 6入力、計20 leaf × 0/1基底 × source offset 0/7 = 各版80停止点。各 step は `[label, raw text, start CP, end CP, line, column, endLine, endColumn]` の独立期待値。空文字・ASCII・非BMP/結合文字・CRLF・同anchorの複数leaf・通常入力との混在・anchorの逆順を含む。実 initialize/launch/configurationDone/stackTrace/scopes/variables/next/continue/disconnect、終了後の状態と breakpoint を照合する。[`rollback.json`](fixtures/generated-token-dap/rollback.json) の5ケースは Java 実 Token と Rust 実 trace で committed / choice 敗者 / 正負先読み / 外側失敗を比較する。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
 -Dtest=TokenTraceConformanceTest,GeneratedTokenDapProtocolConformanceTest,ClassicTokenDapProtocolConformanceTest,ClassicDapProtocolConformanceTest,RustUbnfFrontendConformanceTest,PlaygroundCommandTest \
 -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml --workspace
```

CI は `target/token-trace.tsv`、`target/classic-token-dap-protocol.tsv`、`target/generated-token-dap-protocol.tsv` を必須にする。新しい3テストメソッドも `.github/optin-test-contracts.json` で skip・欠落を拒否する。通常 Maven test だけで Rust 比較済みとは扱わない。性能freeze・root-retry最適化・一般positive assertionの既存差は変更しない。
