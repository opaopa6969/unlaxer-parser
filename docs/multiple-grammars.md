# 同一ファイルの複数 grammar

開発版の #714。公開済み 3.3.0 の成果物・バージョンは変更しない。

Java の `CodegenMain --grammar ...`、Java host の `CodegenMain generate --target rust ...`、
native の `unlaxer generate --target rust ...` は、同一ファイルの各 grammar を独立に生成する。
`check --target rust` は両 host とも全 grammar の inventory と構造を検査する。

## 配置と参照

単一 grammar の Rust 出力は従来の5ファイルのままで、内容・CLI・既存 API を維持する。
複数の場合だけ、元の宣言順の公開 module index `mod.rs` と、各 grammar のサブディレクトリを出す。
名前は `grammar_` に grammar 名の ASCII 小文字表記を連結する。camel case の分割はしない。
例えば `Alpha` と `Beta` は次の配置になる。

```text
generated/
  mod.rs                     # pub mod grammar_alpha; pub mod grammar_beta;
  grammar_alpha/
    mod.rs
    ast.rs
    parser.rs
    mapper.rs
    evaluator.rs
  grammar_beta/
    mod.rs
    ast.rs
    parser.rs
    mapper.rs
    evaluator.rs
```

呼出側では `generated::grammar_alpha::parser::parse_tree(input)`、
`generated::grammar_beta::mapper::map(&tree)` のように選択する。
各 module の内容は、その grammar だけを生成した場合と byte 単位で等しい。
`--lsp` / `--dap` は各サブディレクトリにそれぞれ追加する。
Java の配置は従来どおり `@package` と grammar 名由来のクラス名を使う。
Java host のファイル API は `RustBackend.generateFile(UBNFFile, boolean lsp, boolean dap)`。
従来の `generate(GrammarDecl)` も維持する。native の `generate` / `generate_file` /
`generate_file_with_protocols` は既存の戻り値に相対サブパスを含める。

各 grammar には従来の Rust lowering の対応範囲・構造制約（`@root` が必要など）を個別に適用する。
Java が警告だけで生成する root のないライブラリ grammar まで Rust で新たに受理する変更ではない。

rule・token・mapping・Semantics・生成 grammar の状態は grammar ごとに独立する。同名の
rule や token が別 grammar に存在しても共有しない。別 grammar の rule を暗黙に探索したり、
ファイルの先頭 grammar から参照を補ったりしない。既存の字句 module import の契約は維持する。

この配置を選ぶ理由は、既存の `super::ast` 等の参照と生成テキストを変更せず、追加の
ディレクトリと index だけで隔離できるため。prefix は `self` 等の予約語も有効な module 名にする。
配列番号に依存しないため、別 grammar の追加・順序変更で既存 module 名は変化しない。

## 名前衝突・検証・書き込み

Rust 出力ではファイル内の grammar 名を ASCII 大小文字を無視して一意にする。`Alpha` / `ALPHA`
や同名 grammar は package が異なっても `P-OUTPUT-COLLISION` で拒否する。診断対象は後出 grammar、
subject は衝突した module 名。Java 出力は別の規則で、package と小文字化した生成クラス名の組が
一致したときだけ拒否する。別 package の同名 grammar は従来どおり受理する。同一 package の
大小文字だけ違うクラスは case-insensitive な FS での上書きを防ぐため拒否する。
Java の診断は Java クラス名を示し、既存の validation report / manifest にも記録する。

`check` の span は元ソースの Unicode code point 半開区間で、CRLF と非 BMP 文字をそのまま数える。
生成 CLI の衝突エラーにも `P-OUTPUT-COLLISION [start,end): <出力名>` を含める。
import の link 前の宣言から位置を取り、後続 grammar の生成・link 失敗には名前と元宣言の CP 範囲を付ける。
programmatic AST の非 ASCII grammar 名も `check` では `P-GRAMMAR-NAME` の診断として返す。

各 host はすべての grammar の検証・コード生成をメモリ内で終えてから書き込む。
後続 grammar の意味エラーや evaluator 生成失敗では、先行 grammar の部分出力を残さない。
Rust の書き出しは全成果物の事前検査を行い、手書きファイル、ディレクトリとの衝突、
子ディレクトリの symlink、通常ファイルになっている親を拒否する。Java host は明示した出力先と
生成する子パスの symlink を拒否し、出力先より上の既存 symlink は従来どおり許容する。
native は以前から祖先 symlink も拒否しており、この host 差は残る。
`generate --check` は子ディレクトリを含む生成物を比較し、差分があっても書き換えない。

複数 grammar の root `mod.rs` は全子 module の後に書く。子の書き込みに失敗した場合は root index を
公開・更新せず、I/O エラーに失敗したパスを含める。ただし、それまでに書いた子ファイルの rollback、
OS の書き込み失敗・敵対的な同時変更に対するディレクトリ全体の transaction は保証しない。
以前の grammar を削除・改名した後の不要な生成ファイルも自動削除しない。複数から単一への
変更では配置が変わるため、新しい出力ディレクトリで生成し consumer の参照を更新する。

## 配布物からの利用

開発版 native generator を含む配布物の `unlaxer` と、同じ revision の Rust runtime を使う。
生成時には Java / Maven / Cargo は不要。consumer のコンパイル時には Rust が必要。

```sh
unlaxer check --target rust --grammar languages.ubnf
unlaxer generate --target rust --grammar languages.ubnf --output src/generated
unlaxer generate --target rust --grammar languages.ubnf --output src/generated --check
```

Cargo の runtime 依存と `mod generated;` の設定は [Rust README](../rust/README.md) と同じ。

`playground` / profile は一つの grammar を選ぶ既存契約を維持し、複数 grammar の
暗黙の先頭選択を拒否する。ブラウザ UI の文法切り替えは今回の対象外。

`impact` の schemaVersion 1 は一つの grammar の AST / Semantics 比較用であり、複数 grammar
を直接渡すと Java / Rust target・両 host とも `I-SCHEMA`（終了3）となる。比較対象の grammar を
それぞれ単一 grammar ファイルとして渡す。ファイル全体の module 追加・削除・配置変更は
`impact` の判定範囲に含まれない。[impact 契約](api-impact-report.md)も参照。

import を持つファイルの `check` で、関係のない grammar も含めて診断位置が null 化され、同じ診断が
統合される既存の制限は [#776](https://github.com/opaopa6969/unlaxer-parser/issues/776) で対応する。
import 先の位置を root ファイルの位置と混同しない出典保持が必要なため、今回の生成診断とは分ける。

## 検証範囲

共有入力・期待値は `spec-corpus/multiple-grammars/corpus.json`。
`MultiGrammarConformanceTest` は Java 向けと両 Rust host の CLI 生成物を実際にコンパイルし、
全 grammar の parse / map / eval、原文・capture・CP span、他 grammar の入力の拒否を検証する。
両 Rust host の生成物一致、単一 grammar API の byte 互換、LSP/DAP 付きの実コンパイル、
LF/CRLF、後続 grammar の不正、出力名衝突と部分出力防止も比較する。

```sh
mvn -B -o -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=MultiGrammarConformanceTest,RustPortabilityConformanceTest,RustNativeGeneratorTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --locked --offline --manifest-path rust/Cargo.toml \
  -p unlaxer-generator --test multiple_grammars --test portability
```

CI の Cross-language fixtures に登録し、`target/multiple-grammars-{java,rust,rejections}.tsv`
を証拠として保存する。性能改善は行わず、既存の scaling smoke と単一 grammar 生成物の回帰を確認する。
