# Classic portable token metadata

親 [#111](https://github.com/opaopa6969/unlaxer-parser/issues/111)、この追加 API の受け入れ条件は [#438](https://github.com/opaopa6969/unlaxer-parser/issues/438)。Java の既存 `Token` / `TokenKind` / parser の動作を維持し、Rust に実 word recognition、consume/invert の限定伝播、owned metadata と関連 node の API を追加する。Classic の速度最適化 freeze は変更しない。

## Token の種別と source

| Java | Rust | 消費 | virtual |
|---|---|---|---|
| `consumed` | `Consumed` | あり | false |
| `matchOnly` | `MatchOnly` | なし | false |
| `virtualTokenConsumed` | `VirtualConsumed` | あり | true |
| `virtualTokenMatchOnly` | `VirtualMatchOnly` | なし | true |

virtual のフラグと source の由来は別である。Java `WordParser` は virtual kind でも入力から source を取得する。`ParserCursor` の既存契約では `consumed` だけが consumed cursor から peek し、ほかの3種は matched cursor から peek する。消費する種別は得た source の長さだけ consumed cursor を進め、その位置へ matched cursor を戻す。

例として入力 `ab` を match-only で `a` まで調べた状態は consumed=0 / matched=1。そこで `virtualTokenConsumed` の `b` を読むと、source span は `[1,2)`、両 cursor は1になる。Rust `TokenExpr::Word` もこの観測を保持する。短い入力を invert で成功させず、空 word / EOF での word recognition は失敗する。

collector が新しく生成する detached virtual source は別の経路である。Java `registerGenerated(token, anchor)` / Rust `add_token_node(rule, kind, TokenSource::Generated { anchor, text })` は入力内の CP anchor と任意の生成 text を保持し、cursor を動かさない。CST/input span は anchor のゼロ幅、source text は独立して空でもよい。Java の元 detached source のローカル offset は `Info.sourceOffset` に残し、元 Token も書き換えない。入力由来の virtual token をこのゼロ幅へ変換しない。

## 実 parser の限定伝播

Rust の `ParseContext::parse_token(&TokenExpr, kind, invert)` は通常 context の transaction、呼出し上限、診断と併用する。`Word`、`Sequence`、`Choice`、`Invert`、`StopInvert`、`StopConsume` を提供する。`StopConsume` は Java `DoConsumePropagationStopper` と同様に virtual フラグも含めて種別を `Consumed` に置換し、invert は通す。`StopInvert` は種別を通して invert を false にし、`Invert` は親の値を反転する。これは PEG の失敗反転 lookahead とは異なる。

この入口の transaction は両 cursor を保存する。共通 word fixture の Java 側では `TransactionElement.setResetMatchedWithConsumed(false)` を明示して同じ独立 cursor mode で検証する。Java の通常 transaction の既定 reset=true は変更しない。任意 Java custom parser の全伝播、全 combinator、annotation、UBNF syntax へ対応済みとは扱わない。既存 Rust `Expr::Literal` 等の通常生成 grammar も変更しない。

## Portable metadata と host の差

Java は context ごとに `PortableTokenMetadata` を生成し、`register(token)`、`putExtra(id, name, string)`、`putRelated(id, name, relatedId)` を使う。Rust は `token_node_id(index)`、`put_token_extra`、`put_related_token` を使う。両 API は削除、未知 ID の拒否、snapshot の参照も提供する。portable 値は opaque string、関連先は同一 parse 内の安定 ID。ID は利用者が組み立てず、数値 index は各 host の registration/arena 内の位置であって言語間 wire ID ではない。

Java の既存 `Token.putExtraObject(Name,Object)` / `putRelatedToken(Name,Token)` はそのまま使える。任意 Java Object の identity、関連 Token のオブジェクト参照、deepCopy の既存 shallow side-map コピーは維持する。これら raw maps は従来どおり自動 transaction の対象ではなく、新しい portable map とは独立する。任意 JVM Object の Rust 自動変換は行わない。Rust の任意 cloneable host state は既存 context state API を使い、portable snapshot に含まれると解釈しない。

portable mutation は Java の `MutationAwareTransactionalState` と memo state version、Rust の context checkpoint に接続する。既存 node の情報更新、新規 node の関連付け、choice 敗者、先読み、外側失敗を復元する。Java snapshot は immutable value/maps を所有し、Rust Tree は metadata をコピーして所有する。後続変更、context 破棄、別 parse、snapshot clone で観測が変わらない。Rust Tree の `Send + Sync` も維持する。

rollback された ID は、同じ registration/arena index を再利用しても失効する。別 owner の ID と未登録 ID も拒否する。snapshot は過去の ID とその関連先をそのまま保持する。Rust の longest choice では、敗者 rollback 後に winner の metadata と ID を一緒に復元する。

## 独立期待値と検証

[`words.tsv`](../conformance/token-metadata/words.tsv) は32ケースの入力・4種・invert/stopper・CP span・source・成功失敗・両 cursor・失敗 CP 位置の独立期待値。Java と Rust がそれぞれ同じ期待値へ比較する。Java の実 WordParser / stopper を使用し、Rust の実 TokenExpr を使用する。Unicode、CRLF、virtual consumed の2 cursor 差、EOF、短い invert、空 word、入れ子 stopper、rollback 後の cursor を含む。

[`metadata.tsv`](../conformance/token-metadata/metadata.tsv) は Unicode / CRLF / 空の生成 source の3入力。同じ operation trace で既存値更新、関係削除、新規関係追加、外側 rollback、index 再利用、失効 ID 拒否、owned snapshot を検証する。両 corpus を memo OFF / SafeFailures で検証し、word corpus は Detailed / DetailedOnFailure でも同じ観測を比較する。Rust は実 PEG lookahead / ordered choice / longest choice と自動診断 retry を追加検証する。Java は実 collector と任意 Object の identity 保持を追加検証する。診断 retry の fresh context では ID 自体は別 owner になるが、値・source・位置・関係の意味は同じである。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=PortableTokenMetadataTest,PropagationAlgebraTest,ZeroWidthSourceAnchorTest,NotTest,RustUbnfFrontendConformanceTest,PlaygroundCommandTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DrustConformance=true
cargo test --locked --manifest-path rust/Cargo.toml --workspace
cargo fmt --all --manifest-path rust/Cargo.toml --check
```

frontend の構文は追加していない。Playground の Java/native Rust 配布は共通 runtime と新 module を含める。限定 API の受け入れが完了しても、全 propagation、stateful success memo、incremental、Classic Rust LSP/DAP、Tiny root retry が残る親 #111 は閉じない。
