# 生成parserのスコープ注釈とcapture比較

JavaとRustは、`@scopeTree(mode=lexical|dynamic)`、`@declares(symbol=name, description=doc)`、
文法内に`@scopeTree`がある場合の`@backref(name=name)`を生成parserへ接続する（#176）。
Java frontendからのRust生成と、JVM不要のnative generatorは同じIR・生成コードになる。
`@scopeTree`のない文法の`@backref`も、同一ルールのcapture比較として両言語で生成する（#325）。

## 動作契約

- スコープ付きルールは開始時にenterし、成功時にleaveする。
- 同じルールが宣言も行う場合、leaveしてから親スコープ（なければglobal）へ登録する。
- `symbol` / `name`はそのルール本体のcapture名。パーサークラスが同じ別のtokenから推測しない。
- optional不在はイベントなし。反復では全出現を処理し、入れ子captureは内側の完了から順に処理する。
  別ルール・再帰呼出しの内部captureは、呼出し元のcaptureとして拾わない。
- 宣言後に参照を処理する。未定義参照は`WARNING`（`未定義のシンボル: 'name'`）を残すが、構文解析自体は成功する。
- captureの原文をJava `String.trim()`相当（両端のU+0000〜U+0020）でtrimする。
  引用符・escapeはそのままで、文字列literalの値への変換は行わない。
  offsetは先頭trim分を加算し、offsetとlengthはUnicodeコードポイント単位。空の名前は無視する。
- choice失敗、子成功後の親rollbackによる一時状態はtransactionに従って復元する。
  詳細は[transactional scope store](transactional-scopes.md)を参照。

両modeは現在、**解析時のネストしたスコープスタック**として動作する。
`dynamic`というmetadataは保持するが、評価時の動的環境やclosureを実装したという意味ではない。
`description`もmetadataとして保持するのみで、そのcaptureの存在や値は検証・評価しない。

## APIと移行

Javaは従来の`ScopeStore`と生成metadata query APIを使う。
Rustは生成`RuleEffects`でmode・declaration・referenceを公開し、
`ParseContext.scopes()` / `scopes_mut()`で実状態を扱う。
owned `Tree.scopes()`はtree取得時のsnapshotなので、context破棄・後続解析・rollbackから独立する。
typed AST内にscope storeを埋め込むことやLSP/DAPへの搬送は未実装。
同名nested captureのAST投影差は#177で修正し、内側→外側のlistとして保持する。
[AST型変更と移行](nested-capture-migration.md)を参照。
scope corpusはmappingなしの宣言ルール、nested capture corpusはmapped fieldも含めて両方を検証する。

Java生成parserは再生成が必要。従来の「同じparser classの最初のtoken」を取る挙動や、
対象が見つからないと別tokenを採用する挙動は修正される。
宣言・スコープ参照の対象capture欠損、および重複`@declares`は生成前に明示的な検証エラーになる。
mappingなしの注釈付きルールでもcaptureは利用できるが、mappingのfield/capture整合性検査は緩めない。

## scopeなしのcapture比較

文法全体に`@scopeTree`がなければ、`@backref(name=tag)`はそのルール本体の`tag` captureを
完了順（内側から外側）に比較する。参照先・再帰呼出しの内部captureや、同じparser classを使う
別名のcaptureは混ぜない。最初のtextを基準に、各後続textが異なれば次の意味診断を追加する。

```text
back-reference mismatch: expected 'X' but got 'Y'
```

- severityは`ERROR`。構文の受理・消費位置は変えず、診断を`ScopeStore` / `Tree.scopes()`に残す。
- textは両端のU+0000〜U+0020をtrimする。引用符・escapeは原文のまま比較する。
- 空textも比較に含める。0/1出現なら比較診断なし。optional不在は出現に数えない。
- 診断のoffset/lengthは、異なった後続captureのtrim後のUnicodeコードポイント範囲。
  全体が空白の場合は末尾位置・length 0になる。
- 成功時の意味診断は`Auto` / 詳細診断の失敗時再解析でも保持する。
  branch失敗、親のrollbackによる一時診断は復元し、再解析で重複させない。
- 対象captureは同じルール本体に必要。欠損と重複`@backref`は、scopeの有無によらず検証エラー。

Rustの両生成器は`CaptureEquality` IRから`Expr::compare_captures(name, child)`を出力する。
公開combinatorも利用できるが、比較対象はchildが返すcaptureであり、呼び出したrule内部の
captureは含まない。このeffectは未証明のmemo対象にはしない。
Javaは既存のversion付き`ScopeStore`契約に従って安全な失敗だけをmemo化できるが、
成功したlistenerは省略しない。Rustは比較effectを持つruleとその呼出元をmemo対象から外す。
内部の最適化対象は異なっても、返す構文結果・意味診断・rollback状態を共通入力で照合する。
Rust公開combinatorの`ahead()` / `not_ahead()`は比較の診断とcaptureも復元する。
これはUBNFの文字列patternに対する`LOOKAHEAD`やJavaの`MatchOnly`の伝播契約と同一ではない。

文法内の未使用ruleを含め、どこかに`@scopeTree`があれば、従来のscoped reference契約へ切り替わる。
その場合の`RuleEffects.backref`は宣言との照合であり、未定義参照は`WARNING`になる。
一方、公開Rustの`Expr::Backreference(name)`はcontext全体の最新captureを原文と照合して入力を
消費する別機能である。今回の注釈をcapture-and-replay構文認識や非文脈自由性の証明とは扱わない。

mappingを持たない比較ruleがrootの透過的な投影経路上にある場合、ASTなしのparserも生成できる。
parser成功とmapper成功は別で、AST投影が1 nodeでなければmapperは明示失敗する。
未使用の比較ruleを追加するだけで、無関係なrootのAST制約が緩むことはない。

### Javaの移行

生成parserを再生成する。従来のparser class推測をcapture-site bindingへ置き換えるため、
別名captureの混入、group/nested captureの取りこぼしが修正される。trim後の診断位置と
補助文字を含むlengthもコードポイントで揃える。この修正で診断件数・位置が変わる場合がある。
parserとmapperは同じgenerator revisionで一緒に再生成する。

Rustでは公開`Expr`とcodegen IRのenumに`CaptureEquality`が加わる。
これらを全variant列挙で`match`している利用者は、新variantの扱いも追加する必要がある。

## 検証

`ScopeCaptureBindingTest`はJava実生成parserをコンパイルしてcapture選択と位置を検査する。
`ScopeAnnotationConformanceTest`は共通文法・入力・独立期待値からJava/Rustの受理、cursor、
AST field/span、scope depth、lookup、宣言・参照・診断を比較する。
Java/native frontendの全5生成file一致と、native generatorが空PATHでも動くことを検査し、
結果を`unlaxer-dsl/target/rust-scope-annotations.tsv`へ保存する。CIではTSVを必須artifactとする。

scopeなしの比較は`JavaCaptureEqualityTest`と`CaptureEqualityConformanceTest`で検証する。
共通の[文法・入力と独立期待値](../spec-corpus/capture-equality/README.md)に対し、
受理・両cursor・AST/span・意味診断・rollbackを比較する。native生成の空PATH実行と両hostの
全5生成file一致も検査し、`unlaxer-dsl/target/rust-capture-equality.tsv`をCI必須artifactにする。
