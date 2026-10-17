# unlaxer-ubnf — Rust-native UBNF frontend

Java/JVM/Mavenを起動せず、UBNF全体をowned typed ASTへ構文解析するstd-only crate。
Rust 1.85以上。`parse`はファイルを開かず、import先を解決せず、外部parserをロードせず、
コードの生成・コンパイル・実行もしない。構文を受理しても、Rust backendの対応を意味しない。

```rust
use unlaxer_ubnf::{parse, ElementKind};

let file = parse("grammar G { @root Root ::= 'hello'; }")?;
assert_eq!(file.grammars[0].name, "G");
let element = &file.grammars[0].rules[0].body.alternatives[0].elements[0];
assert_eq!(element.element.kind, ElementKind::Terminal("hello".into()));
# Ok::<(), unlaxer_ubnf::Diagnostic>(())
```

## API・位置・診断

`parse(&str) -> Result<UbnfFile, Diagnostic>`。入力の最後まで検査する。
ASTは入力の借用を持たず、入力文字列破棄後も使用できる。型定義は[`src/ast.rs`](src/ast.rs)。

`parse_with_source(source: impl Into<String>) -> Result<SourceSnapshot, Diagnostic>` は、
同じparseのASTに加えて元入力も所有する（#318）。`snapshot.ast()`、`source()`、
`slice(span)`で参照でき、後続・並列parseで上書きされない。
`AnnotatedElement.capture_span/typeof_span`は付加構文自体、
`GlobalSetting.value_span`は設定値の範囲。Java側の対応APIとsynthetic nodeの扱いは
[source snapshot契約](../../docs/ubnf-source-snapshot.md)を参照。

- declaration、annotation、element、body/sequenceの`span`はtriviaを除く半開区間。
  fileのspanのみ入力全体。`byte_start/end`はUTF-8 byte、`codepoint_start/end`はUnicode scalar数。
- `Diagnostic`はkind/message/spanと1始まりのline/columnを持つ。列はUnicode scalar数。
  CRLFを一つの改行として扱い、CR単体・LFにも対応する。JavaのUTF-16 indexやLSP列ではない。
- 最初のエラーで停止し、成功扱いの部分ASTは返さない。エラー回復は未実装。
  再帰nestingは128まで。超過は`NestingLimit`診断で、panic/stack overflowにしない。
- `RuleBody`は単一alternativeも含め常に`Vec<Sequence>`で表す。Javaの後置`?/*`が
  内部生成する`SequenceBody`と明示括弧の`ChoiceBody`との差は、構造比較時だけ正規化する。
- annotation `Simple`、import、設定、任意parser class名もASTに保持する。
  後続validator/lowererは未対応項目を明示拒否する責任を持つ。

## 構文対応表

基準はJava bootstrap `UBNFAST.java` / `UBNFParsers.java` / `UBNFMapper.java`
（基準revision `666c8cc`）。自己ホスト文法`unlaxer-dsl/grammar/ubnf.ubnf`とspecも照合するが、
説明文・自己ホスト定義と実bootstrapが違う場合は差を明示する。

| 分類 | Rust AST・対応する構文 |
|---|---|
| file/grammar | 複数`grammar NAME { ... }`、空のgrammarも構文として受理（生成可能性は後続validatorで検査） |
| declarations | import→setting→token→ruleの順序、`@import alias from 'path'` |
| settings | `@key: dotted.value`、`@key: { key: 'value' ... }` |
| token（12種類） | Simple(parser class/FQN)、UNTIL、NEGATION、LOOKAHEAD、NEGATIVE_LOOKAHEAD、ANY、EOF、EMPTY、CHAR_RANGE、CI、REGEX、ADAPTER |
| annotation（18種類） | root、mapping(dotted class, params)、eval、whitespace、interleave、backref、scopeTree、declares、catalog、leftAssoc、rightAssoc、precedence、doc、recovery、skip、Simple、commonField、enum |
| element（9種類） | Group、Optional、Repeat、OneOrMore、BoundedRepeat、Separated、Terminal、RuleRef(namespace)、Error |
| element付加構文 | 前置`@typeof(name)`、後置`@capture`、`+ ? * {n} {n,m} {n,} % separator`（suffixは一つ） |
| body | 非空sequenceと`|`choice、入れ子の`() [] {}`、`ERROR('message')` |

字句識別子はASCII `[A-Za-z_][A-Za-z0-9_]*`。整数はASCII数字、通常は上限2147483647。
`ADAPTER('id', version=N)` の version だけは raw 字句を AST に保持し、0 / overflow を
後続 registry が位置付きで拒否する。`@tokenAdapter` は通常の block setting として保持する。
ID / schema / target binding の意味検証は [token adapter 契約](../../docs/token-adapters.md)を参照。
UBNF内triviaはASCII空白（space/tab/LF/CR/VT/FF）と`//`行コメント。
対象言語の`@whitespace: javaStyle`とは別で、UBNF自身の`/*...*/`は未対応として診断する。
文字列はsingle quote。`\n \t \r \\ \'`を復号し、それ以外のescapeはbackslashごと保持する
（REGEX等に必要）。文字列・コメント内のUnicode、実改行も保持する。

`CHAR_RANGE`は現Java APIに合わせ、各境界を1個の非surrogate BMP文字、min≤maxへ制限。
補助文字rangeは明示エラー。`BoundedRepeat.max=None`は上限なし。
一般の意味制約（重複名、参照解決、量指定子min≤max、precedence整合、capture型等）は後続処理。
未知の引数なしannotationは拡張用`Simple`として保存し、未知の引数付きannotation/token constructorは
`UnsupportedSyntax`で拒否する。`@mapping(X)`がzero-fieldであり、`params=[]`は現bootstrap同様拒否する。

## 比較検証と既知差

```sh
cargo +1.85.0 test --manifest-path rust/Cargo.toml -p unlaxer-ubnf
cargo run --manifest-path rust/Cargo.toml -p unlaxer-ubnf --example inspect -- grammar.ubnf
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RustUbnfFrontendConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

`inspect`は検証用で、各ファイルにspanなしcanonical ASTまたは位置付きerrorのJSONを出力する。
公開ASTのwire-format保証ではない。Java比較は新しい独立testクラスに分離し、
`target/rust-ubnf-frontend.tsv`に結果を残す。

共有positive fixtureは全構文種、既存self-host grammar、tinycalc-vscode、cardinality、
evolution文法を比較する。negative fixtureも両frontendへ同じ入力を与える。
`tests/fixtures/known`に残るファイル名は過去の不具合の回帰fixtureであり、現在は
すべてJava/nativeのAST一致検査へ含める。3.3.0公開後の #538 でannotationの差を修正した。

| fixture | 現在のJava/native共通契約 |
|---|---|
| eval-default | strategyは`default`、strip_prefixは独立param。明示strategyは他paramの前後で指定でき、重複引数は拒否 |
| namespace | namespace=`a.b`, name=`Value`を保持（#284で修正済み） |
| keyword-boundary | `@rooted`を`Simple { name: "rooted" }`として保持。引数なし組込みannotationの識別子境界を検査 |
| common-field-space | comma後の空白・改行・行コメントを通常のtriviaとして許容 |
| negative.tsv / trailing-input | 全入力を検査して拒否（#282で修正済み） |

`positive/annotation-parity.ubnf`には27個のprefix名、名前付きeval引数・escape、
commentを含むcommonFieldを保存する。両言語で値を独立期待値と比較し、
source snapshot比較はLF/CRLFと補助文字を含む全ノードのCP範囲・原文を検査する。
Java17向け専用の追加設計は行わず、今後の機能・品質基準はJava21以上とする。

Javaのdiagnosticsは先頭80文字のparse失敗文で、正確なエラー位置は公開しないため位置同値を
主張しない。native位置は独立のUnicode/CRLF/spanテストで検査する。
JavaのUnicode数字・keyword prefixの偶発的受理、空白を含むFQNのraw文字列化は互換保証しない。
古い`unlaxer-dsl/examples/tinycalc.ubnf`のdouble quote設定は現bootstrap構文外。
比較には現行`tinycalc-vscode/grammar/tinycalc.ubnf`を使う。
