# 名前付き汎用 semantic predicate（`@predicate`、#1006）

UBNF の規則注釈 `@predicate('name')` は、規則本体の構文が成功した候補について、呼出し側が名前で登録した純粋な条件を評価する。grammar には Java / Rust のコードを埋め込まず、生成時に callback を呼ばない。Java 生成 parser、Java frontend から生成した Rust、native Rust frontend から生成した Rust の 3 つが同じ契約で動く。既存の `@namePredicate`（固定 version の名前 snapshot、[versioned-name-snapshots.md](versioned-name-snapshots.md)）はそのまま残る。

```ubnf
grammar Octets {
  @whitespace: javaStyle
  token DIGITS ::= CHAR_RANGE('0','9')+;
  @root @mapping(Address, params=[value])
  Start ::= Octet @value | Big @value;
  @predicate('octet')
  @mapping(OctetValue, params=[digits])
  Octet ::= DIGITS @digits;
  @mapping(BigValue, params=[digits])
  Big ::= DIGITS @digits;
}
```

`42` は `Octet`、`300` は `octet` が `false` を返すので `Octet` の候補を巻き戻し、`Big` として受理する。

## 登録と呼出し

Java（生成 mapper）:

```java
Map<String, Predicate<SemanticPredicateBindings.Candidate>> predicates = Map.of(
    "octet", candidate -> candidate.text().strip().matches("[0-9]{1,3}")
        && Integer.parseInt(candidate.text().strip()) <= 255);
Address ast = OctetsMapper.parseWithPredicates(source, predicates, ParseOptions.DEFAULT);
Optional<ParseDiagnostic> diagnostic = OctetsMapper.diagnoseWithPredicates(source, predicates, ParseOptions.DEFAULT);
var mapped = OctetsMapper.parseWithPredicatesAndSourceMap(source, predicates, ParseOptions.DEFAULT);
```

低水準では `ParseContext` を作った直後に `SemanticPredicateBindings.install(context, predicates)` を呼び、`OctetsParsers.getRootParser()`（`SemanticPredicateScope` を含む）で解析し、`SemanticPredicateBindings.failure(context)` で sticky 失敗を読む。

Rust（生成 parser と runtime）:

```rust
use unlaxer_runtime::semantic_predicates::{Bindings, PredicateError};
let predicates = Bindings::new()
    .with("octet", |candidate| candidate.text.trim().parse::<u16>().is_ok_and(|v| v <= 255))?
    .with_fallible("checked", |candidate| lookup(candidate.text).ok_or(PredicateError))?;
let tree = parser::parse_tree_detailed_with_semantic_predicates(source, &predicates, ParseOptions::default())?;
let ast = mapper::map(&tree)?;
```

低水準では `ParseContext::with_semantic_predicates(input, &predicates, options)`（または解析前の `Bindings::install`）と `parse_context`、失敗は `context.semantic_predicate_failure()` で読む。runtime 直呼びは `unlaxer_runtime::parse_detailed_shared_with_semantic_predicates`、式は `Expr::SemanticPredicate { child, name }` と entry 境界 `Expr::SemanticPredicateScope { child, requirements }`。

registry は解析前に所有コピーし（Java `Map.copyOf`、Rust は `Arc` callback の複製）、解析中は差し替えられない。active な scope の中での `install` は拒否する。

## 候補（Candidate）

- Java `record Candidate(String text, int start, int end)`、Rust `Candidate { text: &str, span: Span }`。位置は入力全体の Unicode code point、end は含まない。
- text は規則本体が実際に一致した原文。`@whitespace` の grammar では、規則が自分で読んだ前後の空白・comment も含む（生成 AST の source map span と同じ範囲）。`"\r\n42\r\n"` 全体が `Octet` なら候補は `("\r\n42\r\n", 0, 6)` で、数値として読みたい callback は自分で trim する。
- 空に一致した nullable 規則は、その位置の空 span（例 `("", 5, 5)`）で評価する。
- Classic の MatchOnly / virtual kind・反転でも、consume 前の一致 text を渡す（Java `getRootToken(false).source`。`getConsumed()` は MatchOnly/virtual で空になるため使わない）。
- 投影・生成された token source（入力の連続区間でない text）には元の span がないため、評価せず `predicate_source` で entry を失敗させる。UBNF だけから生成した grammar ではこの経路は起きず、host の custom parser / adapter が生成 token を返した場合に限る。
- 候補は text と span だけで、Java/Rust 共通の CST オブジェクトではない。任意の解析木を読む predicate（#851）はこの API の対象外。

## 失敗の種類

| 状況 | 結果 | 診断 |
|---|---|---|
| callback が `false` | 通常の候補失敗。cursor・CST・capture・登録済み transactional state・scope を巻き戻し、Choice は次の候補を試す | 他候補も尽きれば `syntax`。期待名 `predicate <name>` を候補の終端位置に記録 |
| 宣言された名前が未登録（未到達規則の分も含む） | entry 開始時に本体を解析せず失敗。後の候補の成功で隠れない | `predicate_missing`、offset は entry 開始、期待 `registered predicate <name>`（宣言順で最初の未登録名） |
| callback の例外（Java `RuntimeException`）/ `Err(PredicateError)` / unwind する panic | entry 全体を失敗させる sticky 診断。`false` と区別し fallback で成功扱いしない | `predicate_error`、offset は候補の開始、期待 `predicate <name> completed normally`。アプリケーションの例外文は診断に出さない |
| 生成/投影 source | 同上 | `predicate_source` |
| scope 外での直接評価（手組みの低水準 parser） | 失敗 | `predicate_scope_missing` |
| registry を一切渡さない通常 API（Java `parse`/`diagnose`、Rust `parse_tree_detailed*`） | 黙って成功させない | `predicate_missing`（最初の requirement） |

sticky 失敗の後でも、同じ context に別の registry を `install` すれば再解析できる。Java の `Error`（`StackOverflowError` など）と `ParseLimitException` は `false` に変換せず、既存の `failParserCall` の公開契約どおり伝播する。Rust は callback 自身の unwind だけを `catch_unwind` で捕捉する。`panic = "abort"` の binary では捕捉できずに process が終了するため、アプリケーション側の失敗は `with_fallible` で `Err(PredicateError)` として返す（Rust では例外の代わりに `Result` を正規の経路とし、Java の例外送出と同じ `predicate_error` に対応させる）。parser 一般の panic 後に context を破棄する既存契約は変えない。

## 純粋性・memo・FIRST・診断 retry

- callback は純粋でなければならない。backtracking、同位置での再試行、`DetailedOnFailure` の診断 retry で同じ候補を複数回評価しうる。呼出し回数は契約に含めない。retry は最初の呼出しと同じ registry snapshot を使う。
- Java: entry scope が context の memo と FIRST 候補除外を無効化する（`disableSpeculativeOptimizations`）。生成 wrapper は `DiagnosticsAgnostic` で、診断 retry は通常の grammar と同じ規則で選ばれる。
- Rust: `SemanticPredicate` / `SemanticPredicateScope` は memo unsafe で、それを参照する祖先 rule も unsafe になる。scope の内側では FIRST 除外を止める。述語を含まない子規則の memo は残るが、観測結果は memo OFF/ON・各診断 mode で同一であることを共通 fixture で確認している。
- registry は Rust では typed user state に置き、checkpoint は handle だけを複製する。`ParseContext` の field・通常経路の分岐・確保は増やしていない。新しい `Expr` variant は既存 dispatch の match arm だけである。

## 初版で拒否する組合せ

同じ規則で `@predicate` と並べてよいのは `@root`・`@mapping`・`@doc` だけ。他の規則注釈（`@recovery`、`@leftAssoc`、`@rightAssoc`、`@precedence`、`@whitespace`、`@longestChoice` など）、grammar 内の `@namePredicate`、token-stream / lexical-context grammar、生成 class 名が衝突する規則名（`X` と `XPredicate`）は、両 frontend が生成前に同じ `E-PREDICATE-COMBINATION` で拒否する。黙って注釈を無視しない。ほかに `E-PREDICATE-DUPLICATE`（同じ規則に複数）、`E-PREDICATE-NAME`（ASCII `[A-Za-z_][A-Za-z0-9_.-]*`、1..128 文字以外）、`E-PREDICATE-LIMIT`（異なる名前が 64 を超える）。registry 側も 64 件・同じ名前規則で制限する。`@predicate` の引数なし・引用なし・複数引数・名前付き引数は構文エラー。

## 検証

`unlaxer-dsl/src/test/resources/semantic-predicate/{corpus,invalid}.json` が手書きの独立期待値を持つ（4 文法 21 ケース、拒否 12 件）。`SemanticPredicateConformanceTest`（`-DrustConformance=true` が必要。素の `mvn test` では skip）は、Java 生成 parser/mapper、Java frontend の Rust 生成物、native frontend の Rust 生成物を別々に compile・実行し、prefix cursor、候補 text/span 集合、full-input 受理、AST 全 field・node span、診断 kind/offset/期待名を各診断 mode・memo OFF/ON で照合する（生成物のバイト一致は追加の確認で、それだけを根拠にしない）。さらに「false を無視」「未登録を false で隠す」「retry で registry を失う」「`@predicate` を外す」と期待値が崩れることを同じテストで確認する。実装へ一時的に入れた変異（Java の false 無視・未登録の握りつぶし・wrapper の rollback を commit に置換、Rust の false 無視・未登録の握りつぶし・診断 retry での registry 喪失）はすべて既存テストで失敗する。ただし wrapper 自身の rollback 欠落は、生成 grammar では wrapper が常に transaction を持つ親に包まれるため共通 corpus では観測できず、Java 単体の Optional 直下のケースが検出する。CI core shard で実行し `rust-semantic-predicate.tsv` を必須保存する。runtime 単体は Java `SemanticPredicateParserTest`、Rust `semantic_predicates::tests`、native generator は `rust/unlaxer-generator/tests/semantic_predicate.rs`。

## 制限と残り

- 性能は #197 の凍結に従い未測定。述語を使わない grammar の生成物は変わらず、通常経路に field/分岐を足していないが、速度の合格は主張しない。
- Rust の低水準 `ParseContext::new` など registry を install していない context では、scope は sticky 失敗を残さずに本体を解析せず失敗する（`semantic_predicate_failure()` は `None`）。Java の低水準 `SemanticPredicateBindings.failure(context)` は `predicate_missing` を返す。生成 API と full-input API は両言語とも `predicate_missing` で一致する。
- 生成 LSP / DAP / editor CST（`parse_editor_cst`、Java `parseEditorCst`）と plain の `parse` 系入口は registry を受け取らないため、述語 grammar では本体を解析せず `predicate_missing`（editor では失敗）になる。両言語とも黙って成功させないが、IDE で述語 grammar を使う入口は未提供。
- 候補は text/span のみ。CST を読む汎用 predicate（#851）、`@namePredicate` との併用、上記の注釈組合せは後続の対象。
