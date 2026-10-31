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
    "octet", candidate -> {
        try {
            int value = Integer.parseInt(candidate.text().strip());
            return value >= 0 && value <= 255;
        } catch (NumberFormatException invalid) {
            return false;
        }
    });
Address ast = OctetsMapper.parseWithPredicates(source, predicates, ParseOptions.DEFAULT);
Optional<ParseDiagnostic> diagnostic = OctetsMapper.diagnoseWithPredicates(source, predicates, ParseOptions.DEFAULT);
var mapped = OctetsMapper.parseWithPredicatesAndSourceMap(source, predicates, ParseOptions.DEFAULT);
```

低水準では `ParseContext` を作った直後に `SemanticPredicateBindings.install(context, predicates)` を呼び、`OctetsParsers.getRootParser()`（`SemanticPredicateScope` を含む）で解析し、`SemanticPredicateBindings.failure(context)` で sticky 失敗を読む。`install` は前回の解析の失敗も消す（Rust の `Bindings::install` と同じ）。

生成 mapper の解析入口（`parse` / `parseWithOptions` / `parseWithSourceMap` / `diagnose` / `parseEditorCst` と `*WithPredicates`）は両言語とも必ず `getRootParser()` の scope と wrapper を通る。registry を渡さない入口は `predicate_missing` で失敗し、述語を飛ばして成功することはない。

Java の述語付き規則は `RulePredicateParser` という wrapper class を持ち、規則参照と `getRootParser()` はこの wrapper を指す。既存の規則 class・token class・他の wrapper と名前が重なる場合は、文法だけから決まる規則で `_1`、`_2`… を後ろに付ける（規則の宣言順に、最初の空き名）。たとえば規則 `RangePredicate` がある文法の `@predicate` 規則 `Range` は `RangePredicatePredicateParser` ではなく `RangePredicateParser_1`、token `NUMBER_PREDICATE` がある文法の規則 `Number` は `NumberPredicateParser_1` になる。衝突は拒否しない。

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
- 候補は子が返した Parsed の root 原文で、子が収集した値ではない。Java の `MatchOnly` は自分の token の source を返し、`Choice` と生成規則の `LazyChoice` は選んだ子の `Parsed` をそのまま返す。`Chain`・`Optional`・`LazyChain` は現在の token 種別の token だけを収集する。Rust も同じ Parsed 原文を読む。述語の子を評価する間だけ `capture_owned` と同じ返却 source の記録を有効にし（memo key は記録の有無で分かれる）、Classic の `Choice` / rule が返した原文を候補にする。
- 投影・生成された token source（入力の連続区間でない text）には元の span がないため、評価せず `predicate_source` で entry を失敗させる。text が空でも同じ。診断の位置は、空でない投影ではその論理上の開始（Java `offsetFromRoot` 相当）、生成と空の text では子の開始（Java `emptyAt`）で、host が渡した anchor は使わない。full-input 入口と低水準の Classic 入口で同じ位置になる。UBNF だけから生成した grammar ではこの経路は起きず、host の custom parser / adapter が生成 token を返した場合に限る。
- virtual 種別で何も収集しなかった collector（`Chain`・`Optional`・`EmptyParser`、Rust の `Sequence`・`optional_java`・`JavaEmpty`）は、Java では生成された `""` で、`NamedCapture` / `capture_owned` も GENERATED になる。述語も同じく `predicate_source`（子の開始）で失敗する。
- token 種別と由来は独立である。子が入力由来の空 token を virtual 種別で返した場合（Rust の custom が `TokenSource::Input(at..at)` を `VirtualConsumed` で返す、Java の `context.peek(at, 0)` を `virtualTokenConsumed` で返す）は、直接でも `Choice` 越しでも、どの caller の種別でも候補 `("", at, at)` になる。それを virtual 種別の collector が収集した結果は上の生成 `""` である。prefix が空（at=0）では両者の span と種別が同じになるので、Rust は種別と span から推定せず、collector が公開した token の由来（`TokenSource::Generated`）を返却 source に持たせて判定する。
- 子が token を返さずに成功した場合（Java `PegLookahead` の `Parsed.SUCCEEDED`、Rust の node を返さない match）は、子の前後の cursor の入力区間で評価する。幅 0 なら、その位置の `("", at, at)` になる。Java の `ContainerParser`（hint でない `ErrorMessageParser`、`SuggestsCollectorParser`）が置く空 token は、入力上の幅 0 の view である（`Token.empty` は root context で入力の root を持たないため使わない）。`Optional`・`EmptyParser`・空の `Chain` は、従来どおり入力の空 span になる。
- 子が token を返さない成功の判定は Java の `Parsed` と同じで、`Choice`（Rust では `LongestChoice` / `UniqueLongestChoice` も）とその生成規則（`LazyChoice`）は、token を返さなかった子を選んだときも token を返さない。virtual 種別でも、cursor 位置の入力 `""` として評価する。
- UBNF の `LOOKAHEAD(...)` / `NEGATIVE_LOOKAHEAD(...)` は Classic の `MatchOnly` / `Not` で、PEG assertion ではない。これだけを本体にした規則（1要素の `LazyChain`）では、consumed の収集に一致 text が入らないので、候補はその位置の `""` になる。後ろに消費が続く規則では、消費した原文が候補になる。`R ::= LOOKAHEAD('c') | 'z'` のように複数代替の規則（`LazyChoice`、両 frontend の lowering では `Choice`）では、選んだ `LOOKAHEAD` の token が返るので、候補は一致した `"c"` になる。規則の AST span は従来どおり収集した幅 0 の span である。
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
- registry は Rust では typed user state に置き、checkpoint は handle だけを複製する。`ParseContext` の field は増やしていない。full-input entry の registry 設定は型で特殊化し、述語を渡さない入口の成功経路には registry の確認・分岐・確保を足していない。失敗時だけ、未登録の requirement を調べる。新しい `Expr` variant は既存 dispatch の match arm だけである。

## 初版で拒否する組合せ

同じ規則で `@predicate` と並べてよいのは `@root`・`@mapping`・`@doc` だけ。他の規則注釈（`@recovery`、`@leftAssoc`、`@rightAssoc`、`@precedence`、`@whitespace`、`@longestChoice` など）、grammar 内の `@namePredicate`、token-stream / lexical-context grammar、grammar 設定の `@embedding` / `@embedded`（Java の `embeddedGrammar()` の entry は wrapper の外側の規則 class を指し、registry を受け取らないため）は、両 frontend が生成前に同じ `E-PREDICATE-COMBINATION` で拒否する。黙って注釈を無視しない。embedding の API に registry を渡す方法は初版の対象外。ほかに `E-PREDICATE-DUPLICATE`（同じ規則に複数）、`E-PREDICATE-NAME`（ASCII `[A-Za-z_][A-Za-z0-9_.-]*`、1..128 文字以外）、`E-PREDICATE-LIMIT`（異なる名前が 64 を超える）。registry 側も 64 件・同じ名前規則で制限する。`@predicate` の引数なし・引用なし・二重引用・複数引数・名前付き引数、`@ predicate('x')`（`@` の後に空白）と `@predicates('x')` は構文エラー。`@predicate ('x')` と `@predicate( 'x' )` は受理し、`@predicateExtra` と `@ predicate` は従来どおり生成に作用しない Simple annotation。3.3.0 からの移行は [migration-after-3.3.0.md](migration-after-3.3.0.md)。

本機能の複合不正は、Java の validator・Java 生成器・Java frontend の Rust 生成・native frontend で先頭の診断コードも照合する。token-stream / comment 設定、`@lexicalContext`、`@namePredicate` の検査が `@predicate` の検査より先で、たとえば不正な `@namePredicate` と `@predicate` が並ぶ文法は `E-PREDICATE-COMBINATION` ではなく `E-NAME-PREDICATE-*` を先に出す。native frontend は名前述語をソース構文から事前検証する（#858）。両方を持つ文法では、その検査直後、未定義参照などの宣言検証より前に `@predicate` の組合せを検査する。authoring validator も同じ名前述語の事前検証を使い、lowering の成否に診断順序を依存させない。

## 検証

`unlaxer-dsl/src/test/resources/semantic-predicate/{corpus,invalid}.json` が手書きの独立期待値を持つ（8 文法 38 ケース、拒否 24 件。拒否は最初の error code を照合する）。corpus には wrapper 名が規則 class・token class と重なる文法（生成 Java の compile と両 Rust host の実行を含む）と、astral 文字・ZWJ 家族絵文字・CRLF の後ろで start が 0 でない候補（CP 9..20、false は CP 5、例外は CP 4。UTF-16 や書記素の位置ではない）を含む。7 番目の文法は astral 文字の後ろ（CP 3）で、`LOOKAHEAD` だけの規則、それに消費が続く規則、`NEGATIVE_LOOKAHEAD` だけの規則、nullable 規則に `@predicate` を付け、true の受理・false での fallback・fallback のない false の syntax 失敗を照合する。8 番目の文法は CP 3 で複数代替の規則 `Checked ::= C_AHEAD | 'z' @mark`（`C_AHEAD = LOOKAHEAD('c')`。field は `'z'` の代替だけに付け、先読みの代替の返却 Parsed を変えない）に `@predicate` を付ける。`oneOf:c` では返された `"c"`（CP 3..4）で受理して `CheckedValue`（収集した幅 0 の AST span 3..3、`mark` は null）、`oneOf:`（`""` だけを受理）では同じ `"c"` を拒否して次の代替の `FallbackValue`（3..4）になる。返却 source でなく収集した `""` を候補にする実装は、両方のケースで逆の代替を選ぶ。`SemanticPredicateConformanceTest`（`-DrustConformance=true` が必要。素の `mvn test` では skip）は、Java 生成 parser/mapper、Java frontend の Rust 生成物、native frontend の Rust 生成物を別々に compile・実行し、prefix cursor、候補 text/span 集合、full-input 受理、AST 全 field・node span、診断 kind/offset/期待名を各診断 mode・memo OFF/ON で照合する（生成物のバイト一致は追加の確認で、それだけを根拠にしない）。さらに「false を無視」「未登録を false で隠す」「retry で registry を失う」「`@predicate` を外す」と期待値が崩れることを同じテストで確認する。実装へ一時的に入れた変異（Java の false 無視・未登録の握りつぶし・wrapper の rollback を commit に置換、Rust の false 無視・未登録の握りつぶし・診断 retry での registry 喪失）はすべて既存テストで失敗する。ただし wrapper 自身の rollback 欠落は、生成 grammar では wrapper が常に transaction を持つ親に包まれるため共通 corpus では観測できず、Java 単体の Optional 直下のケースが検出する。CI core shard で実行し `rust-semantic-predicate.tsv` を必須保存する。runtime 単体は Java `SemanticPredicateParserTest`、Rust `semantic_predicates::tests` と `tests/semantic_predicate_source.rs`、native generator は `rust/unlaxer-generator/tests/semantic_predicate.rs`。

返却 source の契約は、Java と Rust の runtime テストが同じ手書き表 `conformance/semantic-predicate-source/{classic,full}.tsv` と、Java 実測から作った `virtual-input.tsv` を読んで照合する（Java `SemanticPredicateParserTest`、Rust `tests/semantic_predicate_source.rs` と `tests/semantic_predicate_virtual_input.rs`）。`classic.tsv` は CP 3 の prefix（`😀\r\n`）の後ろで、低水準 Classic 入口の 4 token 種別 × 27 形（`MatchOnly` と UBNF の `LOOKAHEAD` の直接・`Choice`・`Chain`・`Optional`・生成規則の `LazyChoice` / `LazyChain`、PEG assertion、`EmptyParser`、host anchor 0 で返す生成 text）の 108 行で、候補の text/CP span、受理時の consumed/matched cursor、`predicate_source` の位置を持つ。`full.tsv` は full-input 入口の 21 形で、UBNF の `@predicate R ::= LA | 'z'` / `R ::= LA` の形を含む。どちらも callback が text を読み（`"c"` だけ受理とその逆）、候補が違えば受理が反転することを memo 2 種 × 診断 mode 3 種で確かめる。`virtual-input.tsv` は prefix 0 / 1 / 3（空・`p`・`😀\r\n`）× 4 token 種別 × 29 形の 348 行で、入力由来の空 virtual token（`leaf`）の直接・`Choice`・`Chain`・`Optional`・規則越し、`MatchOnly`、PEG assertion、`EmptyParser`、anchor 0 / cursor の生成 text を持つ。callback の真偽の両方 × memo 2 種 × 診断 mode 3 種で、候補の集合、由来の失敗のときだけ callback が呼ばれないこと、受理時の cursor と rollback 後の prefix 位置を照合する。Rust はさらに、各形が公開した token の `TokenSource`（Input / Generated）と述語の受理・`predicate_source` が一致することを確かめる。

UBNF の lowering（`LOOKAHEAD` は `MatchOnly`、`ERROR` は失敗 hint）は生成 token・PEG assertion・container・virtual 種別に届かないので、それらは共通 corpus では観測できず、上の表と次の単体テストで確かめる。Java と Rust の単体テストは、CP 2 の後ろで次の 2 点を memo 2 種 × 診断 mode 3 種で照合する。Rust では Classic と非 Classic の両方を見る。

- 生成 token の text が `""` でも `"g"` でも、直接返しても `Choice` 越しに返しても、callback を呼ばずに `predicate_source`@2 で失敗し、fallback で回復しない。
- 正負の PEG assertion、`Choice` 越しの assertion、`Optional`・空 span の対照は、true のとき `("", 2, 2)` で cursor 2、false のときは巻き戻して後続の `c` で cursor 3 になり、sticky 失敗を残さない。Java ではさらに container を確かめる。Rust には成功する container がない。

次の変異は、それぞれの単体テストで失敗する（共通 corpus は上記の理由で検出しない）。

- Java: 空を由来の検査より先に受理する、token のない成功も root token の source で判定する、container を `Token.empty` に戻す、由来の検査を外す、生成 source の診断位置を `offsetFromRoot` にする。
- Rust: 由来の検査を `parsed_source` でなく収集後の source で行う、由来の検査を外す。
- Rust（由来、`tests/semantic_predicate_virtual_input.rs`）: 述語の guard に token 種別と空 span による生成の推定を戻す（a53b009d の実装）、collector の返却 source に公開 token の由来（生成）を持たせない、Classic の規則が本体の source をそのまま返す、の各変異は共有表と公開 token との照合テストで失敗する。virtual 種別の `Choice` が記録なしで選んだ子の source を持たない変異は入力 capture 境界のテスト、記録中の規則の生成 `""` を text ありとして読む変異と owned capture の生成 anchor を収集 source の位置にする変異は、virtual 規則の owned capture 位置のテストと既存の owned capture の共有表で失敗する。
- Rust（返却 source）: 述語の子で返却 source を記録しない、候補を収集後の source から読む、生成・空の text の診断位置に host の span を使う、virtual 種別の空の collector を入力扱いする、token を返さない成功を判定しない、生成規則越しの判定をやめる、`Choice` の選んだ子の有無を見ない。いずれも共有表のテストで失敗する。memo の再生で記録した source を戻さない変異は、Rust 単体の memo 境界テストで失敗する。8 番目の corpus 文法は、返却 source を記録しない変異を検出する。memo key から記録の有無を外す変異は観測できない。述語自身の transaction が matched cursor の policy を変えるので、記録なしの結果は述語の中で再生されず、逆向きに再生された source を読む consumer もないためで、memo key の区別は防御として残す。

## 制限と残り

- 既存の profile / schema / `@typeof` などの不正を重ねた場合の一般的な先頭診断の順序は [#917](https://github.com/opaopa6969/unlaxer-parser/issues/917) で継続する。ここで検証した24拒否ケースを、全検証フェーズの一致の根拠にはしない。`@predicate` のない名前述語文法に対する native authoring 検証の不足は [#893](https://github.com/opaopa6969/unlaxer-parser/issues/893) の範囲である。
- 性能は #197 の凍結に従い未測定。述語を使わない grammar の生成物は変わらず、registry のための field/分岐を通常経路に足していないが、速度の合格は主張しない。返却 source の由来の修正（下記）では、Classic の規則 node と meta node に分岐の置換と bool の書込みが加わる。Rust の述語 entry は解析と診断 retry のたびに registry（名前の表）を複製し、root への再帰参照のたびに scope に入り直す。観測結果は Java と一致し、述語を使う grammar だけの費用である（改善は凍結解除後）。
- Rust の editor 補完候補（`editor_cst::literal_completions`）は `SemanticPredicate` / `SemanticPredicateScope` の子を走査する。`@namePredicate` の式は従来どおり走査しない（既存の簡易走査の範囲）。
- Rust の低水準 `ParseContext::new` など registry を install していない context では、scope は sticky 失敗を残さずに本体を解析せず失敗する（`semantic_predicate_failure()` は `None`）。Java の低水準 `SemanticPredicateBindings.failure(context)` は `predicate_missing` を返す。生成 API と full-input API は両言語とも `predicate_missing` で一致する。
- Java の規則 class（`Parser.get(RangeParser.class)`）と `getRootSyntaxParser()` を直接使うと、wrapper と scope を含まないので述語を評価せず成功しうる（mapper が選んだ root token を探すための低水準 API）。Rust の生成規則は本体に `SemanticPredicate` を含むため、同じ使い方でも registry が無ければ失敗する。この差は低水準 API に限り、上記の mapper の解析入口は両言語とも述語を通る。直接使う場合は `getRootParser()` と `SemanticPredicateBindings.install` を使う。
- 生成 LSP / DAP / editor CST（`parse_editor_cst`、Java `parseEditorCst`）と plain の `parse` 系入口は registry を受け取らないため、述語 grammar では本体を解析せず `predicate_missing`（editor では失敗）になる。両言語とも黙って成功させないが、IDE で述語 grammar を使う入口は未提供。
- 由来の検査は、子が返した source（Java は `getRootToken(false).source`、Rust は Parsed 原文）だけを見る。収集する wrapper（Java の `Chain`・`Optional`・`ZeroOrMore`、Rust の `Sequence`・`Optional`）の内側にある生成 token は、wrapper の収集 source に寄与しない（収集は現在の種別の token だけで、空の生成 token は text を持たない）。そのため consumed / matchOnly 種別では、wrapper の結果は cursor 位置の入力の `""` として評価される（Java `Transaction.anchorCollectedEmptySource`、Rust `collected_source`。virtual 種別では上記のとおり生成の `""`）。両言語で同じ挙動だが、入れ子の token 全体の由来を保証するものではない。CST にはその生成 token が残る。
- Rust の full-input 入口（`parse_detailed_*`）は root を Classic にしない。そこで低水準の Classic `MatchOnly` を非 Classic の `Optional` / `optional_java` で包むと、Rust は `MatchOnly` の `"c"`、Java の `Optional` は収集した `""` を返す。同じ差が既存の `capture_owned` + `Replay` でも master から出る（Java `NamedCapture` は `""`）ので、述語固有ではない。UBNF の lowering は `LOOKAHEAD` を非 Classic の `JavaLookahead` にし、その `Optional` は Java と同じ `""` になるので、UBNF からは届かない。低水準の Classic 入口（`parse_with_token_mode`）では Java と一致する。この差は [#1023](https://github.com/opaopa6969/unlaxer-parser/issues/1023) で追跡する。
- host が返す投影 token を `Sequence` で包むと、Rust の full-input 入口の非 Classic `Sequence` は host token の source を収集しない（cursor 位置の入力 `""` で callback を呼ぶ）。Java も解析中に投影 source を作れる（`StringSource.createSubSource(text, context.source, offset)` を token にして返す）。Java の `Chain` はそれを収集し、直接でも `Chain` 越しでも callback を呼ばずに `predicate_source`（投影の論理開始）で失敗する。Rust の低水準 Classic `Chain` も同じく `predicate_source` になる。食い違うのは Rust full-input の `Sequence` だけで、述語はそこで `capture_owned` が保存する値と同じものを見ている（collector 一般の既存差）。UBNF だけから生成した grammar は host の投影 token を返さないので届かない。collector と owned capture の一般の問題として [#1023](https://github.com/opaopa6969/unlaxer-parser/issues/1023) で扱う。
- 低水準 Classic 入口の `capture_owned` も述語と同じ判定で、種別と空 span から生成を推定しない（#1023 C）。token を返さない成功（PEG assertion、それを選んだ `Choice` とその生成規則）は Java の `NamedCapture` と同じく子の後の cursor 位置の入力 `""`、子が返した token はその由来（入力由来の空 virtual token は入力）を記録する。Java 実測の `conformance/owned-capture-virtual/classic.tsv`（prefix 0 / 1 / 3 × 4 token 種別 × 43 形の 516 行）を Java `SemanticPredicateParserTest` と Rust `tests/semantic_predicate_virtual_input.rs` が memo 2 種 × 診断 mode 3 種で読み、Rust は同じ入口の述語の候補が owned の値と一致することも照合する。virtual 種別の `Rule[Choice[..]]` の `InputCaptureBoundary`（`@f` の入力判定）と、host が anchor 0 で返す生成 text の owned anchor（Rust 0、Java cursor）・virtual 種別の `Chain[生成 "g"]` の収集 text（Rust `'g'`、Java `''`）は #1023 で残る。
- 返却 source の記録は、述語の子の評価中だけ有効にする（`track_owned_source`。成功・`false`・sticky 失敗・resource 失敗のどれでも元に戻す）。記録中は、入力由来の rule 結果 source と選んだ子の source の Box を追加で確保する。memo key は記録の有無で分かれるので、述語の内と外で同じ規則の memo を共有しない。述語を使わない解析の field は変わらない（Fragment 56 B / FragmentSource 40 B の const assert を維持）。
- 例外として、低水準 Classic 入口の virtual 種別の `Choice` / `LongestChoice` / `UniqueLongestChoice` は、記録していないときも選んだ子の source を Box で複製して返却 source に持つ。virtual 種別で何も収集しなかった collector の内部 source を公開 token と同じ生成 `""` にしたため、これを持たないと同じ入口の `Choice[MatchOnly]` 等の `@f` / `InputCaptureBoundary` が入力由来の子を生成と誤判定する（Java の Choice は選んだ子の Parsed を返す）。consumed / matchOnly 種別と full-input 入口の通常経路、UBNF の生成 grammar には届かない。性能は計測しておらず、主張もしない。
- 候補は text/span のみ。CST を読む汎用 predicate（#851）、`@namePredicate` との併用、上記の注釈組合せは後続の対象。
