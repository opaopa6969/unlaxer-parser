# 固定版の言語 profile と限定 UBNF

追跡 #433、親 #370 / #383。`language-profiles/` は、Java 21、TypeScript 5.9.3、
Rust 1.85.0 edition2021 の**明示した部分文法**を収録する。対象言語の完全な parser、
型検査器、実行環境として扱わない。受理したソースの意味的正しさは保証せず、拒否した
ソースが公式処理系でも不正とは限らない。

| ID / artifact版 | source grammar | 公開 entry | 部分対応の中心 |
|---|---|---|---|
| lang/java@0.1.0 | Java21.ubnf | CompilationUnit / Type / Expression / Statement / Block | package、import、class、field、method、型付き引数、return、呼出し |
| lang/typescript@0.1.0 | TypeScript59.ubnf | SourceFile / Type / Expression / Statement / Block | function、型付きlet/const、return、呼出し |
| lang/rust@0.1.0 | Rust185.ubnf | Crate / Type / Expression / Statement / Block | fn、型付きlet、i32/i64/bool、末尾式、呼出し |

artifact版はこの UBNF/profile の版、対象版は解析対象、tool版は比較する公式処理系の版。
Java 21.0.9 の `javac`、TypeScript 5.9.3、Rust 1.85.0 を明示し、暗黙のlatestを使わない。
Java preview、TSX、Rust proc macro/build.rs の実行は有効にしない。

すべて原著の限定文法で、第三者grammarを転載・変換したものではない。固定版の仕様・
実装を比較対象として参照する。Java corpus の `tiny69-1/2` はユーザー所有の
`tinyexpression/src/test/resources/formulaInfo-test/69/formulaInfo.txt` にある実Java本文を
そのまま抽出したもの。依存型の解決を要求しない構文比較で、package/import/class/method
を使う既存の入口を維持する。抽出元commitは `f86ce8a5ab0ab7d23fdba2cd477187df8aa7607c`、
元fixtureのSHA-256は `5eeecb314c39cf1bc96378b627126494ff6b0bebc9753dfa2d28d43336aff4ec`。

## profile 契約

`profile.tsv` は UTF-8 / LF、最大64 KiB、512行。重複key、未知行種別・状態、欠落能力、
浮動artifact版、親path参照、実行能力の有効化を拒否する。すべての能力を明示する。
`SUPPORTED` / `PARTIAL` / `EXTERNAL` / `UNSUPPORTED` は宣言値であり、
`EXTERNAL` を読んでもproviderを登録・起動しない。未登録providerの応答状態は既存の
`UNAVAILABLE` のままで、文法不一致と区別する。

Java `LanguageProfile`、Rust `language_profile::LanguageProfile` が同じprofileを読み、
`identity(entry)` で `LanguageRegions.Language` / `source::Language` を作る。
従来のregion/source-map/query/providerが持つ language/package/version/grammar/entry と
同じ値であり、ASTの名前や呼出し側の推測からidentityを作らない。

`entry` は公開する入口、`syntax` は構文群の範囲、`capability` は解析・編集等の能力。
`source` は固定参照先とrevision、`fixture` は独立期待値、`difference` は既知差分。
CLIは行を安定順に並べて表示する。

```sh
# Java CodegenMain / native unlaxer の両方で同じサブコマンド
unlaxer profile --file language-profiles/java/profile.tsv
unlaxer playground --profile language-profiles/java/profile.tsv --output /tmp/java21-playground
cd /tmp/java21-playground
node build.mjs
npm start
```

`--profile` と `--grammar` は排他。profileで指定した同じディレクトリのgrammarを読み、
文法名・公開entryを検証して生成する。生成物にcanonical profileを同梱し、ブラウザで
対象版、部分対応、外部解析器の必要性、未対応操作を表示する。生成・表示は通信も解析器の
自動導入も行わない。外部compilerをWASM内で実行できるとは表示しない。

## 構文と意味・対応差分

識別子はASCIIに限定し、予約語を除外する。文字列本文・コメントにはUnicodeを保持する。
文字列escapeは引用符、backslash、n/r/tの限定集合。数値は十進非負整数のみ。
式は認識用の演算子列で、優先順位付きの実行ASTではない。ASTは`Source`と元文書の
CP spanを保持し、詳細構造はCSTを使う。名前・型・scopeをこの文法から推測しない。

Javaのrecord/enum/generics/annotation/Unicode escape、TypeScriptのASI/TSX/union型/
継続行/string template、Rustのmacro/token tree/所有権構文/raw string等は対象外。
Javaの同名String宣言など、一部の有効な識別子も保守的に拒否する。TSの文末semicolonを
必須とし、Rustのpub修飾や型も列挙した範囲に限る。これらの差分を未対応として公開し、
ANYでsource全体を受け入れて完全対応とはしない。

## 検証と上限

`corpus.tsv` は39件の初期caseに予約語・式文3件を加えた42件。
正例、未閉鎖、予約語境界、公式言語では有効な未対応構文、型不適合を分ける。
公開入口ごとの受理/拒否、CP長、AST/CST root span、未閉鎖のEOF失敗位置を
Java生成・Java RustBackend・native Rust生成で比較する。生成Rustファイルはbyte一致を要求する。

公式比較では構文と意味診断を分離する。Java/TSはPARSE後に必要なVALIDATEを行い、
Rust stableは独立したparse-only APIを提供しないためVALIDATEと独立した期待分類を使う。
Rustをparse-only検証済みとは表示しない。両hostが実providerを呼び、診断種別・severity・
Unicode位置の全件一致を確認する。動的classpathや利用者コードの実行は加えない。

`mutations.tsv` はseed384383から各言語32回の編集系列（正常/閉じ括弧削除）と
深さ1/8/32/64の括弧式を生成する計204件。日本語、絵文字、結合文字、補助面、CRLFを含む。
変更前の期待値を生成parserから採取しない。再現コマンドは次の通り。

```sh
python3 scripts/language-profile-corpus.py --check
mvn -pl unlaxer-common,unlaxer-dsl -am test -Dtest=LanguageProfileConformanceTest,PlaygroundCommandTest,RustUbnfFrontendConformanceTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-runtime --test language_profile
```

固定CI環境はUbuntu、JDK21.0.9、Node20.20.0、Rust1.85.0。compiler adapterは30秒、
各生成/compile/browser検証processは120秒で打ち切る。Playgroundは入力64 KiB、解析3秒、
WASM最大memory256 MiB。profile読込上限とparser全体のメモリ保証は区別する。
時間切れ・上限超過はruntime/provider状態として表示し、構文不一致へ変換しない。

`language-profiles` CI は3言語それぞれの実WASMをChromiumで開き、能力表示、成功、拒否、
mobile表示を検証する。`target/language-profile-*.tsv` を成果物として保存する。

## 親issueの残り

このsliceで #370 / #383 をcloseしない。ruleを公開するpackage importは既存のtoken専用
importと別契約であり、まだ追加していない。生成LSPでのprofile選択、FormulaInfo全体への
本番登録、言語固有typed completion・editのprofile corpus、より広い構文の実装が残る。
既存のprovider/region/edit corpusが成功しても、その言語の全機能の根拠へ広げない。

### 固定 package の明示選択

`lang/java@0.1.0`、`lang/typescript@0.1.0`、`lang/rust@0.1.0` は、上記の原著限定文法・profile・corpus を同梱した source-only artifact です。
artifact の再生成は `python3 scripts/language-profile-packages.py`、一致検査は `--check` を使用します。
依存 manifest には通常の exact version を指定します。

```json
{"schemaVersion":1,"dependencies":{"lang/java":{"version":"0.1.0","source":"builtin:lang/java@0.1.0"}}}
```

Java `CodegenMain` と native `unlaxer` に共通の手順です。

```text
deps resolve --manifest /project/ubnf.json
playground --package lang/java --manifest /project/ubnf.json --output /project/java-playground
```

`deps resolve` だけが取得・lock/cache 更新を行います。Playground 生成は既存 lock/hash と artifact の ID/版・依存 graph を検査し、artifact の `entry` を root 文法として選択します。
`entry` と同じディレクトリの `profile.tsv`、profile の grammar/公開 entry/fixture、package identity が一致しなければ生成しません。profile は capability の説明であり provider の登録や host code の実行を行いません。
ローカル配布物や HTTPS で取得済みの artifact も同じ契約です。生成物の `public/package.json` には検証した ID、版、SHA-256、entry file を保存します。

この root 選択は `@import alias from 'pkg:…'` と別の API です。`@import` は引き続き宣言的 token のみを公開し、rules を持つ言語 package の import は拒否します。
選んだ root 自身は lock 内の token package を import でき、その定義元と pinned identity は `public/vocabulary.json` に引き継ぎます。
`--grammar`、`--profile`、`--package` は排他で、`--manifest` は `--package` と対で指定します。
生成 LSP への profile 設定、外部 provider の自動配線、language package の typed completion はこの入口とは別の後続作業です。
