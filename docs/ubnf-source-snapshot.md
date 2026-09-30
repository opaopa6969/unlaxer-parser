# UBNF source snapshot — Java / Rust

Issue: [#318](https://github.com/opaopa6969/unlaxer-parser/issues/318)
（移植可能性診断 [#157](https://github.com/opaopa6969/unlaxer-parser/issues/157) の基盤）。

## 目的と境界

構文解析時に得られた位置を、semantic AST に変換した後も保持する。
元の source を再検索したり、もう一度 lex/parse したりせず、同じ parse から snapshot を作る。
同値のリテラルが複数の場所にあっても別の位置を保持する。

これは診断用の位置保持 API であり、未対応機能を全件報告する portability check ではない。
後者の集約・JSON/CLI・exit status は #157 に残る。
生成 parser の入力位置や LSP の UTF-16 列とも別の、**UBNF ファイル自体の位置**である。

## API

Java（既存の `parse`、`parseWithImports`、`UBNFAST` record の形は変更しない）:

```java
UBNFSourceSnapshot snapshot = UBNFMapper.parseWithSource(source);
var grammar = snapshot.ast().grammars().get(0);
var rule = grammar.rules().get(0);
var span = snapshot.spanOf(rule).orElseThrow();
String originalRule = snapshot.slice(span);
var origin = snapshot.originOf(rule).orElseThrow();
```

- `ast()` は同じ parse から構築した semantic AST、`source()` は元入力。
- `spanOf(node)` / `originOf(node)` / `sourceOf(node)` は **ノードの同一性**で検索する。
  record の値の等しさでは検索しない。別 parse のノードや手製ノードは `Optional.empty()`。
- `captureSpan(annotatedElement)` は `@capture` 自体の範囲。
  `@typeof(name)` は `spanOf(element.typeofConstraint().orElseThrow())` で取得する。
- index は呼び出しごとに作り、snapshot 内へコピーする。parse context・Token・parser の
  参照は保持せず、static map / ThreadLocal も使わない。以降の成功・失敗・並列 parse は
  snapshot を変更しない。AST は既存 record API のままであり、AST 自体の可変性を
  この API が変更するわけではない。
- source index が必要ない既存 `parse` では位置の収集を行わない。

Rust（従来の `parse(&str)` も維持する）:

```rust
let snapshot = unlaxer_ubnf::parse_with_source(source)?;
let grammar = &snapshot.ast().grammars[0];
let rule = &grammar.rules[0];
let original_rule = snapshot.slice(rule.span).unwrap();
# Ok::<(), unlaxer_ubnf::Diagnostic>(())
```

- `SourceSnapshot` は `String` と `UbnfFile` を所有し、読み取り参照を返す。
- 既存 AST の `span` に加え、`AnnotatedElement.capture_span / typeof_span` と
  `GlobalSetting.value_span` を公開する。これらは通常の `parse` でも得られる。
  Rust の struct literal を手書きする利用者は新フィールドの指定が必要になる。
- `slice(span)` は UTF-8 byte 境界を使い、不正な範囲・文字の途中なら `None`。
  Java の `slice` は code-point 範囲を使い、範囲外なら例外となる。

## 範囲の契約

- 半開区間。共通座標は Unicode code point。Java は `Span(start, end)`、
  Rust は `Span.codepoint_start/end`。Rust はさらに UTF-8 byte 範囲を持つ。
- file はコメント・空白を含む入力全体。それ以外は外側の trivia を除き、
  構文の先頭から末尾まで。内部の空白・コメントは元のまま含む。
- grammar/import/setting/value/setting entry/token/rule/annotation/body/sequence/
  atomic element/annotated element/capture/typeof を対象にする。
  名前や annotation 引数の String 自体は独立した AST ノードではなく、包含する
  構文ノードの範囲で参照する。
- Java は実際の CST の original children から句読点を保持し、UBNF delimiter の
  subtree だけを除く。quoted literal 内の空白や `//` は除去しない。
- LF / CRLF、補助文字、escape のある入力を**元テキストの座標のまま**扱う。
  escape 復号後の文字列長や Java UTF-16 長から範囲を逆算しない。

Java の `Origin.kind`:

| kind | 意味 |
|---|---|
| `SOURCE` | 対応する構文から直接取得した範囲 |
| `SYNTHETIC` | 後置 `?/*` のために mapper が作る SequenceBody / AnnotatedElement。内側の atomic element の範囲を継承 |
| `REWRITTEN` | Java mapper に既存の `@typeof` capture 補正で再構成したノード。補正後の構文範囲を保持 |

Rust は `@typeof` を最初から prefix として parse するため Java 固有の補正は行わない。
後置 `?/*` の内側に作る RuleBody / Sequence / AnnotatedElement は、
Java の補助ノードと同じく内側の atomic element の範囲を持つ。
Java の補助 SequenceBody と Rust の単一 alternative RuleBody の形の差は、
conformance oracle 内で明示的に対応付ける。範囲や元テキストは正規化しない。
由来不明のノードへ `0..0` を割り当てることはしない。

## import と失敗

この API は単一の入力だけを parse し、import のファイルを開かない。
import 先も必要ならファイルごとに snapshot を作り、呼び出し側でファイル ID と組み合わせる。
既存 `parseWithImports` が名前空間を付けて統合した AST の位置は、この snapshot の対象外。
複数ファイルを統合した provenance は未実装として扱う。

失敗時は従来どおり Java の例外 / Rust の `Diagnostic` を返し、
部分的な成功 snapshot は返さない。Rust の既存 nesting limit や、
既知の Java frontend の構文・mapper 差をこの機能が解消するわけではない。

## 検証

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=UBNFSourceSnapshotTest,RustUbnfFrontendConformanceTest,RustUbnfSourceConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false

cargo test --locked --manifest-path rust/Cargo.toml
cargo clippy --locked --manifest-path rust/Cargo.toml --workspace --all-targets -- -D warnings
```

- Java unit test: 外側 trivia・Unicode・同値別位置・synthetic/rewritten の由来、
  後続失敗・並列 parse 後の保持、import API 非変更。
- Rust unit test: owned input・後続失敗・並列 parse 後の保持、UTF-8/code-point の
  両範囲から同じテキストが得られること、capture/typeof と不正 byte 境界。
- 共通 corpus: 既存全 positive fixture、namespace、位置専用 fixture、
  self-host UBNF、tinycalc、cardinality、evolution 4 段階を LF / CRLF で比較。
  semantic AST は旧 parse と一致し、Java/Rust の全ノード・付加構文の
  code-point 範囲と元テキストを path ごとに比較する。
- `rust-ubnf-source.tsv` は CI で生成・存在確認し、conformance artifact に含める。
  素の `mvn test` では Rust 比較は skip するため、明示フラグ付きで検証する。
