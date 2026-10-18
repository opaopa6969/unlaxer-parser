# 既存 auto-eval と nested mapped leaf の合成 (#583)

基準製品は`bc3f0b19`（Java21 / Rust1.85）。製品や新しい形を追加せず、既存監査が
compile/mapまで確認した2経路の**evaluator実行**を共通の手書き期待値で補います。

| 文法 | 既存の自動評価 | 呼出側が実装するcallback |
| --- | --- | --- |
| `LiteralLeaf` | scalar textのLeafだけ`@eval(kind='literal')` | nested `List<Optional<Leaf>>`のRoot（`@eval`無し）。presentのLeafを評価し、emptyを保持する |
| `PassthroughLeaf` | Rootだけ`@eval(kind='passthrough')` | nested `List<Optional<Text>>`のLeaf（`@eval`無し）。RootはNodeへ委譲し、`convertValue`を呼ばない |

ここで手書きcallbackとは`@eval`を付けない既存abstract methodの実装です。
`@eval(..., strategy='manual')`をnested ownerへ追加する機能ではありません。
nested owner自身への自動評価/assoc/tokenStream等の制約は変更しません。
既存`TypedNestedCaptureConformanceTest.unsupportedShapesFailBeforeEmissionWithSourceOrigins`も
実行し、`typed-nested-captures/invalid.json`のauto-eval ownerがJava/Rust両generatorで
`E-NESTED-SHAPE`と元のCP位置を保って拒否されることを確認します。

## 期待値の出所と意味

`*-cases.json`は生成parser/evaluatorの出力からsnapshot化していません。
文法と明示的なcallback契約から、値、callback順序、選択ASTのCP span、semantic failureの
spanを手書き定義しました。入力は空、😀重複、family+結合文字、失敗途中/先頭、全文拒否です。

- `👨‍👩‍👧‍👦`は7CP/11UTF16/25UTF8、`é`は2CP/2UTF16/3UTF8。
  連結はCP9、選ばれる最後のliteral LeafはCP7..9です。grapheme単位へ契約を変えません。
- 空の繰返しは既存nullable repeat契約の`[None]`を保ち、callbackは`none`を記録します。
- `!`は呼出側callbackが要求するsemantic failureです。後続Leaf/empty callbackを実行しません。
  scalar auto-literalはそのLeafのspan、manual nested Leafはcallbackを受けたLeaf全体のspanを
  failure位置とします。manual callbackの内側textを別AST nodeと偽りません。
- Javaはidiomaticな例外、Rustは`Result::Err`で同じ値/イベント/位置を表します。
- 2文法はnullableでprefixを受理できます。`?`/`😀?`は全文consumerのEOF条件でCP0/1拒否とし、
  evaluatorを呼びません。Java direct parserのprefix APIとRust full-input helperの契約を区別します。

## Preferred と保持snapshot

valid入力ごとにdefault/`Leaf` preferredの両方を評価します。literal grammarのpreferredは
最後のLeaf、passthrough grammarでは唯一のnested Leafです。literal空入力ではLeafが無くRootへfallbackします。

Javaはparser contextをclose、Rustは元CST treeをdropしてから初回evalします。
その後同じclassesで`!😀`を別parse/mapし、旧snapshotと再選択した同一ASTを再evalします。
同値Leaf😀がCP0..1から別文書CP1..2へ現れるため、recordの値の一致をidentity/spanの一致と誤認すれば検出できます。
Javaのsource mapは既存どおり位置を保持し、原入力の所有を新しく保証する変更ではありません。

## 実行数と経路

2文法×7入力×memo OFF/ON×diagnostics3。
各文法のvalid5入力はpreferred2×phase2で120観測、全文拒否2入力は12観測です。
**Java264観測、各Rust host264観測、計792観測**。retained phaseでは保持済みviewと再選択viewの両方を確認します。
観測行数はeval呼出し数とは異なり、各consumerでtop-level evalを360回（計1,080回）実行します。

Javaは実javac21でparser/AST/mapper/evaluatorとtyped consumerをcompileします。
RustはJava/native両hostの全生成file名・byteを比較し、それぞれの生成物とtyped Semantics consumerを
rustc1.85でcompileして独立に実行します。新しいruntime依存は追加していません。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=NestedAutoEvalConformanceTest,TypedNestedCaptureConformanceTest#unsupportedShapesFailBeforeEmissionWithSourceOrigins \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

`unlaxer-dsl/target/nested-auto-eval-java.tsv`は265行、
`unlaxer-dsl/target/nested-auto-eval-rust.tsv`は529行（各header1行）です。
この限定合成で差がないことは、全nested cross product、新しいnested-owner autoeval、
任意host callbackの自動移植、親#583全ACやmergeの完了を意味しません。
