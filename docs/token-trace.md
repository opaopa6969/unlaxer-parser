# Classic の実 terminal trace と token DAP

[#463](https://github.com/opaopa6969/unlaxer-parser/issues/463) は、Java の実 `Token.filteredChildren` の leaf と、Rust の実認識中に記録する leaf metadata を接続する。CST の rule/capture node を token と見なしたり、解析後に原文を再解析して leaf を作ったりしない。通常の CST・AST・評価器・構文認識は変更しない。

```rust
use unlaxer_runtime::{ParseOptions, token_trace::Limits};
let options = ParseOptions::default().with_token_trace(Limits::default());
let tree = generated::parser::parse_tree_with_options(source, options)?;
let leaves = tree.token_trace().expect("explicitly enabled")?;
```

`ParseContext::token_trace()` / `Tree::token_trace()` は未指定なら `None`。指定時は完全な committed trace または `Error` を返し、不完全な prefix を完全な trace として返さない。`Tree` は source と metadata を所有し、後続操作・rollback・context破棄から独立する。既存 CST arena へ trace 用 node を追加しない。trace は既存内部 metadata store に置き、通常の `Tree` の Debug 表示も維持する。`ParseOptions` は新しい opt-in field を持つ。

各 `Leaf` は label、4種の `TokenKind`、`TokenSource`、その leaf が認識された直後の consumed/matched CP cursor を保持する。`span()` はCPの半開区間（Projected は連結本文の論理範囲）、`text(original_source)` は原文または独立した generated / projected text を返す。virtual と source の由来は独立で、入力由来の virtual consumed は通常の input span を持つ。先に match-only で `a` を読んだ入力 `ab` で virtual consumed の `b` を読む場合、source `[1,2)` に対し両 cursor は1となる。

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

`Expr::Custom` / `CustomWith` は成功時に `declare_complete_token_trace()` を明示しない限り `Unsupported` となる。一部の通常 Expr を呼んだだけでは対応と見なさない。宣言は「この callback の全認識が trace 対応 Expr / TokenExpr / 明示 terminal・generated token 登録で記録され、raw cursor 移動を隠さない」という実装者の契約である。入れ子 callback は個別に宣言する。誤った宣言を自動証明するAPIではない。未知 adapter を黙って trace 対応と見なさない。

既存 failure memo は trace の成功結果をキャッシュしない。失敗時の trace rollback 後に memo を保存し、hit で失敗候補の leaf を復活させない。共通fixtureで OFF の hit=0 / ON の実 hit=1 を確認する。Java の leaf cursor 観測は observer を付けると memo が無効になるため、memoを使う2回の実行と、独立 callback で各 leaf cursor を測る3回目を分ける。Rust の観測 accessor はその context の failure memo hit 数のみを返す。

## DAP

生成 `dap::backend()` は `ParserBackend` を返す。`runtimeMode: "token"`（既定）または `steppingMode: "token"` で生成 parser の trace を使い、`"ast"` は既存 strict mapper を使う。`AstBackend` と既存 custom Backend は引き続きAST専用。custom Backend が token に対応する場合は `supports_token_trace()` を明示する。

token mode では consumed / virtual consumed の input-backed / generated / projected leaf を committed trace の順で巡回する。同じ anchor の leaf も統合・ソートせず、それぞれ停止する。`Current Token` scope、`Token` variable、`runtimeMode`、`steppingMode` を返し、AST件数は0。位置変換・breakpoint・next・continue・source offset・client基底は [AST DAP と共通](classic-rust-dap.md)。これは解析後の構造 inspection であり、live evaluator の実行停止ではない。

### generated token の本文と原文 anchor（#863）

`TokenSource::Input(span)` の本文は原文 slice。`TokenSource::Generated { anchor, text }` の本文は独立した `text` であり、空文字も有効。原文の空 slice で代用しない。`anchor` は原文上の CP 位置で、DAP の stack frame は `[anchor, anchor)` を原文 snapshot から UTF-16 へ変換する。generated text の長さ・改行・非 BMP 文字は原文の開始/終了位置を動かさない。source は launch した program、breakpoint は同じ anchor の原文行を使う。CRLF 内部の anchor は保持し、位置変換を必要とする request だけを下記 #899 の契約で失敗させる。原文外の anchor は引き続き拒否する。

Rust の公開経路は、`ParseOptions::with_token_trace` を有効にした `ParseContext` で `add_token_node(..., TokenKind::VirtualConsumed, TokenSource::Generated { anchor, text })` を呼び、成功した完全な `Tree` を `unlaxer_dap::token_steps(&tree)` へ渡す。`Backend::supports_token_trace()` を `true` にし、`Backend::steps` からこの結果を返す。[実行可能な custom backend](../rust/unlaxer-dap/examples/generated_token_dap.rs)を参照。custom callback 内なら `declare_complete_token_trace()` も必要で、宣言漏れを無視して成功にしない。match-only leaf、choice 敗者、先読み・外側失敗の leaf は停止対象にならない。

`Step.generated_text` は `Some(String)` なら generated。両本文 field が `None` のとき input-backed となる。`token_steps` は trace が所有する本文を複製するので Tree/context 破棄後も表示できる。generated の非ゼロ幅 span と AST mode への generated text は拒否する。Projected の対応は下記 #889 のとおり。trace limit（件数・UTF-8 bytes）、未指定・不完全 trace、parse の resource failure は正常な空 trace に置換せず、stderr・terminated・exited で終了する。

Java の既存生成 DAP は `token.source.sourceAsString().strip()` を variables に表示する。一方、素の detached source の offset は0、長さは生成本文の CP 長であり、`PortableTokenMetadata.registerGenerated(token, anchor)` の位置は自動では使われない。共通 `ascii-mixed` fixture で、原文 `ab`、anchor 1、生成本文 `R` の既定表示が本文 `"R"`・原文 CP `[0,1)` になることを、変更前の実 stdio で確認した。

既存の任意 Token 表示と collector を維持し、生成 Java DAP に `protected int[] tokenSourceSpan(Token token)` を追加した。既定は従来の source offset/長さを返す。generated source の所有者は、成功した token に対応する `PortableTokenMetadata.Info` の `[offset(), end()]` をこの hook で返す。stackTrace と breakpoint の両方がこの範囲を使い、variables は既存の本文経路を使う。[共通 fixture の Java consumer](fixtures/generated-token-dap/JavaMain.java.txt)は実 custom collector・detached Token・metadata を使ってこの接続を実行する。原文と generated text を同じ source に捏造しない。表示上の strip と引用符 escape は両版で同じで、保持している raw text は変更しない。

開発版の移行: 手組みの Rust `Step { label, span }` には input/AST 用の `generated_text: None` を追加する。`--dap` 生成物は Java host / native host とも再生成する。Java の hook を使う consumer も DAP を再生成する。公開済み 3.3.0 と parser のホットパスは変更しない。

### projected token の本文と論理 CP 範囲（#889）

Java の既存経路（PR #887 merge 後 `60debd99`）で、`TokenList.toSource(SourceKind.subSource)` が連結した source を `new Token(kind, source, parser)` の terminal として返す custom collector を、生成 DAP の実 stdio で先に実行した。原文 `abc` の CP `[1,2)` を2回連結すると本文 `bb`、source の論理 CP 範囲 `[1,3)` になり、variables は `"bb"`、stackTrace は原文の UTF-16 column 2–4 を表示する。原文 slice `bc` へ置換されない。Projected は到達可能であり、対象外とはしない。Java の生成 DAP と既存 parser の実装・既定位置は変更していない。

Rust の `TokenSource::Projected { span, text }` はこの連結本文と論理 CP 範囲に対応する。`token_steps` は本文を `Step.projected_text: Some(String)` に所有し、空文字も保持する。stackTrace / breakpoint は `span` を原文 snapshot で UTF-16 に変換し、variables は保持した本文を Java と同じ strip / 引用符 escape で表示する。本文内の改行や非 BMP 文字から原文位置を作り直さない。source は launch した program を指す。逆転範囲・範囲外の開始は従来の snapshot 検査で拒否する。CRLF 内部の位置は下記 #899 に従い保持する。論理終端が原文末尾を越える場合は下記 #892 の契約に従う。

#892 は選択肢1（Rust を Java に合わせる）を採用した。Java が参照実装で、既存の launch・表示・停止順を維持するためである。PR #891 merge 後 `a555deb7` の Java 実 stdio で、原文 `abc` の `[2,3)` を2回連結した本文 `cc`・論理範囲 `[2,4)` は launch / entry が成功し、その step の `stackTrace` だけが失敗することを再確認した。Rust の prepare は、開始位置が原文内で、論理幅と所有本文の CP 長が一致する Projected の終端超過だけを保持する。位置は切り詰めない。不正な手組みの幅、input / generated / AST の範囲外、逆転範囲、trace 上限・不完全 trace の拒否は維持する。

失敗 response は `success:false`、`message:"Internal error."`、**文字列**の body とし、先頭行は `java.lang.IllegalArgumentException: outside snapshot` に揃える。Java の body は続いて Java/JDK の呼出履歴を含む。Rust は互換診断の先頭行と改行を返し、JVM の呼出履歴を捏造しない。共通比較は body の型と先頭行を固定し、Java 固有の `\tat ...` 行のみを除外する。失敗の反復で step は進まず、scope と所有本文の variables は表示できる。next は後続 step へ、continue は後続 breakpoint または正常終了へ進む。最終 step が範囲外でも stdout の Completed → terminated → exited(exitCode=0) で終わる。共通 fixture の終了後は空の stackFrames / scopes、runtimeMode / steppingMode のみの variables、next / continue の失敗となる。終了後に Rust が runtimeMode を除外していた点も Java に合わせた。他の失敗 response も下記 #900 の共通 oracle で message・body の型と先頭行を比較する。Java の製品コードと parser のホットパス、公開済み 3.3.0 は変更していない。

公開経路は opt-in trace を持つ `ParseContext` の `add_traced_token_node(rule, label, kind, source)` → 成功した `Tree` → `unlaxer_dap::token_steps` → custom `Backend::steps` である。[実行可能な consumer](../rust/unlaxer-dap/examples/projected_token_dap.rs)を参照。既存 `add_token_node` は Projected terminal を trace に記録しないため、custom terminal の明示登録だけを新設した。既存 parser のホットパスに呼出・分岐を追加しない。新 API は cursor を動かさず、元 API と同じ source 検査を行い、Input / Projected / Generated を指定 label で1回だけ記録する。trace の件数・本文 UTF-8 bytes 上限と transaction rollback をそのまま使う。custom callback では全認識を記録したうえで `declare_complete_token_trace()` が必要であり、新 API はその宣言を暗黙に行わない。

`generated_text` と `projected_text` の同時指定、および AST mode での projected text は拒否する。Generated のゼロ幅 anchor 検査も維持する。match-only leaf は停止対象外で、choice 敗者・正負先読み・外側失敗の terminal は rollback する。未指定 / 不完全 trace・上限・resource failure は stderr、terminated、exited で明示終了する。手組み `Step` には `projected_text: None` を追加し、生成 DAP は Java host / native Rust host とも再生成する。

### CRLF 内部の位置と request の失敗（#899 / #900）

#899 は選択肢1を採用し、**Java の既存挙動に合わせた**。基準は PR #898 merge 後 `1a8c8921`。原文 `A\r\nB` の CP 2 は CRLF 内部である。Input `A\r` / `\nB`、Projected の開始 `[2,6)` と終端 `[0,2)`、Generated の anchor 2 を launch で拒否せず、本文・論理範囲を保持する。該当 step の `stackTrace` は `success:false`、`message:"Internal error."`、文字列 body の先頭行 `java.lang.IllegalArgumentException: inside CRLF` で失敗する。終端超過もある `[2,6)` では、Java と同じく開始の `inside CRLF` が先に出る。位置の補正や切り詰めはしない。variables / scopes は表示でき、next で後続 step または正常終了に進める。

continue が空の breakpoint 集合でも失敗するのは Java の既存挙動であり、新しい推奨仕様として設計したものではない。根拠は [`DAPProtocolEmitter.emitContinue`](../unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/DAPProtocolEmitter.java) が無条件に `findBreakpointIndex(stepIndex)` を呼び、[`DAPRuntimeEmitter.emitBreakpointHelpers`](../unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/DAPRuntimeEmitter.java) が `breakpointLines.contains(getLineForStep(i))` の引数を先に評価することである。後続 step の**開始**が CRLF 内部なら、その探索を通る continue が同じ直接例外で失敗し、現在位置は変わらない。現在 step の開始だけが不正で、後続が正常なら continue は成功する。終端だけの不正は breakpoint 探索を失敗させない。configurationDone は stopOnEntry が false かつ breakpoint があるときだけ探索し、失敗しても準備済みの step 0 を保持する。request の再実行は「新しい launch が必要」の失敗となり、next / continue は保持された状態から操作できる。breakpoint なし・stopOnEntry false では正常終了する。これらを実 stdio で固定した。Java 製品コード、公開3.3.0、parser ホットパスは変更しない。

#900 の一覧と独立期待値は [DAP 失敗応答 fixture](fixtures/dap-failures/README.md) を参照。Classic AST / Classic token（Rust は両生成 host）/ Generated / Projected の実 stdio で、initialize 前・launch 後の未配置・停止中・終了後を比較する。不正 thread / frame / variablesReference、null / 省略引数（arguments 自体の省略も含む）、停止していない next / continue、不正 launch、重複 configurationDone、LSP4J の未実装31 request、未知 command を含む。Java には initialize 前の一律拒否がなく、Rust も request ごとの判定に合わせる。省略された primitive frameId は 0、引数 object 自体の null は失敗する。

意図した差は JVM 固有の診断詳細だけである。Java の呼出履歴（CompletionException の原因節を含む）は比較せず、Rust は互換先頭行と改行を返す。`setBreakpoints(null) / null breakpoint` の helpful NPE は Java が JVM のメソッド名・parameter 名を含む説明、Rust が `java.lang.NullPointerException: missing setBreakpoints arguments` / `null breakpoint` を返す。message・文字列 body 型・先頭行の例外型は一致させ、説明だけを意図した差として parity ledger に記録する。その他の fixture の失敗先頭行は全文一致させる。任意の不正 JSON 型・壊れた framing の全列挙はこの request 契約の対象外で、既存 transport の上限と検証を保持する。

## 独立期待値と実行

- [`leaves.tsv`](../conformance/token-trace/leaves.tsv): 40入力。Java実Token / Rust実traceで source・kind・CP span・text・最終両cursor・各leaf両cursorを照合する。再実行、4種、consume/invert、sequence/choice/longest/predictive/lookahead、Number/Identifier/quoted、空要素、失敗rollback、memo hit を含む。
- [`generated.tsv`](../conformance/token-trace/generated.tsv): generated virtual の anchor/text/kind/cursor を3入力で独立比較。
- [`classic-token-dap`](fixtures/classic-token-dap/): 8入力、0/1基底、source offset 0/7、実Java/Rust stdio DAP。UTF-16、CRLF、EOF、breakpoint/next/continue、variables、無効request、失敗relaunchを確認。両host生成6ファイルのbyte一致も検証。
- [`generated-token-dap/cases.json`](fixtures/generated-token-dap/cases.json): 6入力、計20 leaf × 0/1基底 × source offset 0/7 = 各版80停止点。各 step は `[label, raw text, start CP, end CP, line, column, endLine, endColumn]` の独立期待値。空文字・ASCII・非BMP/結合文字・CRLF・同anchorの複数leaf・通常入力との混在・anchorの逆順を含む。実 initialize/launch/configurationDone/stackTrace/scopes/variables/next/continue/disconnect、終了後の状態と breakpoint を照合する。[`rollback.json`](fixtures/generated-token-dap/rollback.json) の5ケースは Java 実 Token と Rust 実 trace で committed / choice 敗者 / 正負先読み / 外側失敗を比較する。

- [`projected-token-dap/cases.json`](fixtures/projected-token-dap/cases.json): 8入力、20 leaf × 0/1基底 × source offset 0/7 = 各版80停止点。空文字・ASCII・非BMP/結合文字・CRLF・本文と原文の改行差・input/generated 混在・逆順・strip/引用符 escape・先頭の空 part（Java `TokenList.toSource` と同じく論理範囲は最初の非空 part から始まる） を共通 stdio で確認する。Java fixture は実 token の raw text / CP 範囲も独立期待値に照合する。[`rollback.json`](fixtures/projected-token-dap/rollback.json) は5ケースの Java terminal / Rust trace の本文・範囲・rollback を固定する。

- [`projected-token-dap/overflow.json`](fixtures/projected-token-dap/overflow.json): #892 の4入力・11 leaf。ASCII の `[2,4)`、非 BMP、先頭の空 part と entry での失敗、CRLF を含む最終 step の失敗を固定する。0/1基底 × source offset 0/7 × next / 失敗後 continue / breakpoint 巡回の各48 session・116停止点を実 stdio で比較し、各48終了状態も検証する。Rust 単体テストでも同じ fixture の CP 範囲・失敗応答・variables・終了を検証する。

- [`projected-token-dap/crlf.json`](fixtures/projected-token-dap/crlf.json): #899 の3入力に Generated anchor と先頭 Projected を追加した5入力。0/1基底 × source offset 0/7 × next / continue / breakpoint 付き continue / breakpoint 付き configurationDone / 一致しない負の breakpoint 行での configurationDone / breakpoint なし直接実行の各120 session。失敗の反復・不変な停止位置・変数・next による復帰・終了を比較する。Rust 単体テストも同じ fixture を使う。
- [`dap-failures/requests.json`](fixtures/dap-failures/requests.json): #900 の各 request の success・message・body 型・先頭行を独立に固定する。`target/dap-failures-*.tsv` に全 route の比較一覧と生 response、`target/token-dap-crlf.tsv` に #899 の比較を出力し、CI artifact を必須化する。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
 -Dtest=TokenTraceConformanceTest,GeneratedTokenDapProtocolConformanceTest,ProjectedTokenDapProtocolConformanceTest,ClassicTokenDapProtocolConformanceTest,ClassicDapProtocolConformanceTest,RustUbnfFrontendConformanceTest,PlaygroundCommandTest \
 -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml --workspace
```

CI は `target/token-trace.tsv`、`target/classic-token-dap-protocol.tsv`、`target/generated-token-dap-protocol.tsv`、`target/projected-token-dap-protocol.tsv`、`target/projected-token-dap-overflow.tsv` を必須にする。Generated の3メソッドと Projected の4メソッドも `.github/optin-test-contracts.json` で skip・欠落を拒否する。通常 Maven test だけで Rust 比較済みとは扱わない。性能freeze・root-retry最適化・一般positive assertionの既存差は変更しない。
