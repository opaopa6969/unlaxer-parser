# Classic Sequence の trivia と collected source

Classic entry (`parse_with_token_mode`) で trivia を有効にした Sequence は、Java 生成 parser の delimiter と同じく、先頭・各要素間・末尾の trivia を子 token として評価し、親 token kind に対応する source を収集します。通常の Consumed では原文の空白やコメントを含むため、cursor、CST の source、そこから map した AST の CP 範囲が一致します。

例えば `Identifier 'B'` に javaStyle trivia を適用すると、`A B` は consumed/matched=3/3、root text=`A B`、root span=[0,3] です。` A B ` は 5/5、text=` A B `、span=[0,5] です。以前の Rust 明示 Classic entry は cursor だけを進め、root を `AB` / [0,2] または [1,3] に投影していました。同じ入口の Java の観測値に揃えています。通常の generated parse entry と native scanner の skip 処理は従来どおりです。

## delimiter と projection の境界

javaStyle delimiter は Java generator と同じ `ZeroOrMore(Choice(SpaceParser, CPPComment, BlockComment))` の順序です。SpaceParser は space、tab、LF、VT、FF、CR の 1 CP を受け取ります。行コメントの終端は CRLF、CR、LF、EOF の順に検査します。宣言 trivia は一つの字句プログラムを `ZeroOrMore` で評価します。delimiter 内では外側 trivia と contextual lexer を一時的に外し、自分自身を再度 delimiter で分割しません。成功・失敗・資源制限のどの結果でも外側設定を復元します。

4 token kind、inversion、consume stopper の意味は [Classic token propagation](classic-token-propagation.md) を引き継ぎます。virtual token や異なる kind の子を持つ collector の source は、原文の連続 slice になるとは限りません。parent と同じ kind の source だけを連結した Projected source の長さ・位置を維持します。コメント内の終端検査は default mode で行われるため、virtual Consumed のコメントは通常 Consumed と同じ結果にはなりません。例えば共通 oracle の `A//😀\rB` は virtual Consumed では text=`A//😀B`、consumed/matched=5/5 です。この既存 Java 契約を通常の原文 slice に置換しません。

宣言 trivia の inversion は `LexicalTokenParser` と同じ空の assertion です。WordParser の反転のように固定長を消費する契約とは区別します。明示 Classic entry では wrapper の外側で単純に span を広げるのではなく、各 child の token kind と位置を使って収集します。atomic token 内部の字句境界は [Classic atomic trivia](classic-atomic-trivia.md) のままです。

## 検証

`conformance/token-propagation/sequence-trivia.tsv` は独立期待値 81 ケースを持ちます。Java の共通 runtime の実 delimiter と DSL の `LexicalTokenParser`、Rust の対応 graph が同じ入力・成功失敗・consumed/matched CP・root/committed source を比較します。4 kind、inversion、stopper、先行補助文字、matched と consumed のずれ、空 delimiter、space/tab/CRLF、block/line comment、EOF、失敗した Choice の rollback、recovery を含みます。memo 2 種・diagnostics 3 種の双方で検証します。

Rust は trace 有無も比較します。成功した source の retained leaf 連結、失敗 branch の trace rollback、trivia が trace 上限に達した場合の parse/source 不変性と代替 branch の budget 復元、既存 recovery の trace 非対応状態を確認します。内部の資源制限時は trivia・contextual lexer 設定、mode、前後 cursor を復元します。native runtime の既存 recovery/source/trace 回帰も実行します。

`AtomicTriviaConformanceTest` は `generated-atomic-trivia.json` の 8 文法・32 入力に対し、Java と native の両 host の Rust 出力一致、両言語の実 compile、通常入口と明示 Classic CST→mapper の AST/source CP を比較します。javaStyle と宣言 trivia、Identifier / Number / SingleQuoted / DoubleQuoted を含みます。source map の比較は cursor の一致だけを根拠にしません。

固定 TinyExpression `7d7bd1cbfab1bd9548ba53c73cc50502f0437c67` の実 Java token でも、`TinyCodeFenceConformanceTest` の14文法・86入力/route（FQNとadapter）を検証します。外側 trivia のある文法を含め、通常入口と明示 Classic CST→mapper の canonical AST 値・CP範囲を比較し、Java oracle と一致させます。比較対象からの除外はありません。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=GeneralTokenPropagationTest,GeneralLexicalPropagationTest,AtomicTriviaConformanceTest,RuleTriviaConformanceTest,ClassicTokenDapProtocolConformanceTest,RecoveryConformanceTest,RustUbnfFrontendConformanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DrustConformance=true
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml -p unlaxer-runtime
```
