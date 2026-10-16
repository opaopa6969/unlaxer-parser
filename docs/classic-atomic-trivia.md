# Classic 組込 token と外側 trivia の境界

`Expr::Identifier`、`Expr::Number`、`Expr::Quoted('\'')` / `Expr::Quoted('"')` は、Classic の token-kind / inversion を内部の leaf parser へ伝播します。ただし外側文法の whitespace / 宣言 trivia は、内部 leaf 間には適用しません。Java の各組込 parser は grammar delimiter を持たない内部 Chain を使用しており、Rust も同じ字句境界を使用します。

| 入力と parser | consumed / matched（通常の Consumed） | token の原文 |
|---|---|---|
| `A B` / Identifier | 1 / 1 | `A` |
| `1 2` / Number | 1 / 1 | `1` |
| `1e 2` / Number | 1 / 1 | `1` |
| `'a b'` / SingleQuoted | 5 / 5 | `'a b'` |
| `'a\ b'` / SingleQuoted | 6 / 6 | `'a\ b'` |

直接 atomic parser を呼んだ場合、先頭や末尾の空白も自動では消費しません。外側の `Sequence([Identifier, Literal("B")])` に trivia を有効化した場合は `A B` を全体として受理します。これは二つの構文要素の間にある空白を処理する、既存の sequence 契約です。quote 内の空白・comment に見える文字・CRLF・補助文字は原文として保持します。

4 種の token kind と inversion、consume/invert stopper の既存の cursor・projection 契約は維持します。特に quoted parser の delimiter は `Consumed` かつ inversion false の stopper を持つため、MatchOnly の quoted parser を「読み進めてから巻き戻す scanner」とは扱いません。詳細は [Classic token propagation](classic-token-propagation.md) を参照してください。

Rust の内部字句 scope は whitespace と宣言 trivia の両方を保存し、成功・構文失敗・内側の資源上限失敗後に復元します。外側 choice の rollback や後続 sequence の trivia を無効化しません。一般の native scanner API の動作は変更しません。Classic 中の FIRST / failure memo の保守的扱いも既存契約のままです。

## 検証

`conformance/token-propagation/atomic-trivia.tsv` の独立 oracle は、52 ケースの受理拒否、前後 CP cursor、診断位置、原文・projection、raw result / committed token / retained leaf を Java21 と Rust1.85 で比較します。4 kind、inversion、stopper、先行補助文字、空白/comment/CRLF/escape を含みます。Rust は whitespace と宣言 trivia、trace 有無、memo/diagnostics の組合せで同じ期待値を使い、retained trace の原文連結も検査します。

`generated-atomic-trivia.json` の 8 文法・32 入力は、`@whitespace: javaStyle` と宣言 trivia の下で Identifier / Number / SingleQuoted / DoubleQuoted を使用します。`AtomicTriviaConformanceTest` は両 source host の Rust 出力一致、Java/Rust 実 compile・prefix cursor・全入力受理拒否・AST/source CP を比較し、Rust の通常入口と明示 Classic 入口も一致させます。

これは既存の AST capture の整形規則を変更しません。group capture は group 内の delimiter trivia を含み得ます。また既存の mapper は single quote を取り除きますが double quote は保持します。共通期待値はこれらの既存契約を含めています。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=GeneralTokenPropagationTest,AtomicTriviaConformanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DrustConformance=true
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml -p unlaxer-runtime
```

#601 は既存の組込 token の字句境界を修正する単位です。新しい UBNF token-mode 構文や、全外部 parser の自動変換は追加しません。

## 外側 source collection

#606 で明示 Classic entry の外側 Sequence も delimiter trivia を collected source に含むようになりました。通常 Consumed の `A B` は Java/Rust とも root text=`A B`、span=[0,3] です。`AtomicTriviaConformanceTest` は明示 Classic CST を mapper に渡した AST/source CP も共通期待値と比較します。virtual / Projected の既存規則との区別は [Classic Sequence の trivia](classic-sequence-trivia.md) を参照してください。
