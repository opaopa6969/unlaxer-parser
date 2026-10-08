# 文脈付き字句 goal

`@lexicalContext(tokens=['ID','SHIFT'], literals=['(',')'])` は規則の実行中だけ有効な terminal 集合を選ぶ。v2 の宣言的 token と通常の literal を再利用し、特定言語の文字列クラスを追加しない。選択した literal の順、次いで token の文法内宣言順で比較し、最長の完全一致が勝つ。同じ長さの一致は最初の定義を使う。空・重複・nullable token、空・重複 literal、未定義 selector、256 terminal を超える goal、重複 annotation は拒否する。空集合は制御用 EOF / EMPTY だけを許す。

```ubnf
@mapping(TypeExpression, params=[name,arguments])
@lexicalContext(tokens=['ID'], literals=['<','>',','])
Type ::= ID @name [ '<' Type @arguments { ',' Type @arguments } '>' ];

@lexicalContext(tokens=['ID','SHIFT'])
ShiftExpression ::= ID SHIFT ID;
```

同じ `>>` が型引数では `>` 二つ、式では SHIFT 一つになる。規則呼び出しで goal を enter し、成功・失敗・先読み・choice の試行から戻る時には caller の goal を必ず復元する。独立した子文法の entry は caller の goal と lexical session を使わない。Java の `Lexing.withIndependentLexing` と Rust の nested `parse_shared_grammar` は raw source の独立 entry を提供する。

| 機能 | 既存 / 変換 / 拡張 | この変更の範囲 |
|---|---|---|
| 優先順位・結合性 | 既存の rule graph と assoc annotation | 既存仕様を保持 |
| 左再帰 | 前方試行前に非左再帰の rule graph へ変換 | 左再帰を runtime に追加しない |
| long code fence | 既存の host token / 宣言的 CAPTURE・SAME_AS | 既存入口を保持し、新 goal は宣言的 token を使う |
| PEG の choice | 既存の順序付き / longest / predictive choice | 選択順を文法で明示 |
| 一致時の LOOK / NOT、CAPTURE / SAME_AS | 既存の原子的な宣言的 lexical program | goal 内でも private capture と rollback を保持 |
| `>>`、division / regex、文字列 / JSX 本文 | scoped lexical goal の runtime 拡張 | 明示 terminal 集合を切替 |
| 改行、固定 indent | 既存 BOL / EOL / literal / lexical sequence | 原文の CRLF と CP / UTF-16 位置を保持 |
| 動的 indent stack / dedent、ASI | lexer state または文法変換が追加で必要 | 自動挿入しない |
| TSX の `<T>` の全曖昧性、template substitution | parser と goal の追加文法が必要 | 共通 fixture の明示的な部分集合のみ |
| grammar import | 既存 lexical module linker | selector の module alias を定義元で解決 |
| 子言語 | 独立 entry / snapshot | goal/session は境界を越えて継承しない |

scannerless の通常 entry と `@tokenStream: enabled` の DIRECT / TRIVIA_CACHE / TOKENS_LAZY / TOKENS_EAGER は、active goal では同じ最大一致を行う。context を使う token profile は raw token boundary を goal ごとに調べ、eager な base inventory が作った別の境界を解析の制約にしない。rule の空白方針が必要な時だけ trivia を読み、`@whitespace(none)` の文字列 / JSX 本文の先頭空白を保存する。名前付き lexical trivia は caller の goal/session から独立して評価する。ただし tokenStream の global / rule-local 名前付き trivia は既存の明示的拒否を保持し、scannerless の対応表にだけ載せる。

goal の token cache は terminal program と raw offset を key に持つ。trivia の cache は token 選択から独立する。Java は enter/exit を memo state version に反映し、Rust は active goal の間に failure memo を使わない。memo の on/off による観測可能な結果は一致する。`Session.lexemes()` は既存の base-goal 静的 inventory であり、実際に選んだ goal の結果・位置は CST / capture / AST を使う。静的 inventory が別の token 分割を含んでも、原文の lossless inventory として維持する。

参照 parser は既存 package-lock が固定する TypeScript 5.9.3 を使う。[公式 scanner](https://raw.githubusercontent.com/microsoft/TypeScript/v5.9.3/src/compiler/scanner.ts) は greater-than / slash / JSX の再走査と試行時の状態復元を公開し、[公式 parser](https://raw.githubusercontent.com/microsoft/TypeScript/v5.9.3/src/compiler/parser.ts) が文脈を決める。ここでは同じ型 alias、shift、division、regex、文字列、JSX 入力を両生成 parser とその固定版 parser に渡す。対象は宣言した小さい構文部分集合であり、完全な TypeScript parser の互換性を主張しない。型・変数の未定義は構文受理と別扱いになる。未閉鎖の原子的 token の診断は token 開始位置を指すため、TypeScript の回復診断位置とは異なり得る。

## 再現

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
 -Dtest=ContextualLexingConformanceTest,RustUbnfFrontendConformanceTest \
 -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml
cd unlaxer-dsl/ubnf-vscode
npm ci
node scripts/contextual-lexing-reference.mjs
node scripts/contextual-lexing-wasm.mjs
```

手書き oracle は `spec-corpus/contextual-lexing/corpus.json`（29 入力）、`runtime.json`（6 境界入力）、`invalid.json`（8 宣言、LF / CRLF）である。生成 Java / native Rust の全ファイル一致、受理、消費、AST / capture の CP span、Unicode、CRLF、失敗位置、import、goal の nested rollback を比較する。TEXT の mapper 値は従来どおり外側の trivia を trim するが、capture span は元ソースを保持する。証跡は `unlaxer-dsl/target/rust-contextual-lexing.tsv` 、`rust-contextual-lexing-invalid.tsv`、`rust-contextual-lexing-boundaries.tsv` と `unlaxer-dsl/ubnf-vscode/target/contextual-lexing-reference.tsv`、`contextual-lexing-wasm.tsv`。WASM の 226 比較も同じ原文・手書き oracle で実行し、生成 Playground の同梱 runtime は既存 Chromium 検証で実際に build / 実行する。
