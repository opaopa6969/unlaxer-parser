# Java / Rust Classic の本番品質基準

対象は **3.3.0** の、対応表に記載した Classic の機能である。公開検証は [#522](https://github.com/opaopa6969/unlaxer-parser/issues/522) と[配布記録](../release/3.3.0-verification.json)に保存した。
Maven Central の 3.2.0 に以下の修正が含まれるという意味ではない。
全 Java 文法との互換性、未対応 IDE 機能、任意の負荷に対する性能は別の条件である。

## 受け入れ条件

1. Java/Rust の共通 corpus で受理・拒否、原文、CP span、UTF-16/UTF-8位置を独立期待値と比較する。
2. 空入力・EOF・CRLF・補助文字・結合文字・書記素列、および途中位置・範囲外を検証する。位置の単位を暗黙に変更しない。
3. 深い再帰や再入を制限し、資源上限を公開する。失敗後に未完了 transaction の副作用を残さない。
4. LSP/DAP は実際の framed stdio 通信で lifecycle、重複要求、無効要求、対象sourceを確認する。応答だけでなくprocess終了も確認する。
5. 生成ソースを両hostでコンパイル・実行する。Rust fmt/Clippy、Java 17/21、Rust 1.85 の既存回帰を通す。skipは成功に数えない。
6. 修正前の失敗を再現し、担当外レビューを行い、issueごとのPRをmergeする。

## 今回の監査と修正

| issue | 問題と検証する契約 |
|---|---|
| [#503](https://github.com/opaopa6969/unlaxer-parser/issues/503) | Java source位置のEOF・最終行・CRLF。両言語共通55境界でCP/UTF-16/UTF-8を照合 |
| [#504](https://github.com/opaopa6969/unlaxer-parser/issues/504) | DAP `configurationDone` 再送によるstep巻戻り・終了後のstopped通知。両言語で再送を拒否 |
| [#505](https://github.com/opaopa6969/unlaxer-parser/issues/505) | Java REGEXのUTF-16一致幅をCPとして消費する不具合、matchOnly位置、反転不一致 |
| [#506](https://github.com/opaopa6969/unlaxer-parser/issues/506) | Rustの再帰wrapperと公開grammar再入で資源上限を迂回する問題 |
| [#507](https://github.com/opaopa6969/unlaxer-parser/issues/507) | DAPがbreakpointのsourceを無視して別ファイルの設定で上書きする問題 |
| [#508](https://github.com/opaopa6969/unlaxer-parser/issues/508) | LSPのshutdown後要求・exit・組込みhostとstdio processの終了契約 |
| [#509](https://github.com/opaopa6969/unlaxer-parser/issues/509) | snapshot逆位置変換の二乗走査。単一走査にし、3,906文字列の全境界でも独立照合 |
| [#510](https://github.com/opaopa6969/unlaxer-parser/issues/510) | Unicode 17の書記素parserと位置変換。公式766ケース、CP parserとの混在、生成ASTの原文保存 |
| [#516](https://github.com/opaopa6969/unlaxer-parser/issues/516) | Java再帰上限と解析中断時のtransaction終了 |

Javaの公開履歴・API範囲はRustより広い。一方で今回の監査ではJavaにも位置・REGEX・lifecycle・再帰の不具合があり、公開年数だけで品質を判定しない。
Rustの汎用REGEX未対応は継続する。#505のRust literal/ANY共通corpus成功を、REGEX対応の証拠にはしない。

## 位置・文字の契約

既存のparser cursorとCST/AST spanはCP単位のままである。Javaの`char`はUTF-16 code unit、Rustの`char`はUnicode scalarなので、跨言語APIでは「char位置」という曖昧な名前を使わない。

`👨‍👩‍👧‍👦`は7CP・11 UTF-16 code units・25 UTF-8 bytes・1 extended grapheme clusterである。
graphemeは表示幅ではなく、フォント・端末の列数を計算しない。Unicode正規化も行わない。
位置変換表は元snapshotに結び付く。編集後は新snapshotで作り直し、同じURIでもversion/contentが違う表を流用しない。
Gへのstrict変換では書記素の途中を受理せず、丸めが必要な用途だけ明示的なfloor/ceilingを使う。CP/UTF-16/UTF-8間の変換は従来どおりscalar境界を扱う。
APIとコード例は[書記素座標の説明](grapheme-coordinates.md)を参照する。長さの変換には開始位置が必要である。`ab`も`e\u0301`も2CPだが、それぞれ2Gと1Gなので、範囲の両端を変換して差を取る。

## 再現手順

Linux x86_64、JDK 21（Java 17 artifactはJDK 17）、Rust 1.85、Maven、Node/npm、Chromiumを使用する。
ブラウザ/provider検証には先に以下を実行する。

```sh
cd unlaxer-dsl/ubnf-vscode
npm ci --ignore-scripts
npx playwright install chromium
```

リポジトリルートで実行する。`tinyexpression` はCIと同じ `7d7bd1cbfab1bd9548ba53c73cc50502f0437c67` の独立checkoutを用意し、現在のunlaxerを指定してcompileしておく。既存checkoutの未commit変更を上書きしない。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am install -DskipTests -Dgpg.skip=true
mvn -B -f /absolute/path/to/tinyexpression/pom.xml compile \
  -Dunlaxer.version=3.3.0 -Dtinyexpression.skipRailroad=true

cargo fmt --all --manifest-path rust/Cargo.toml --check
cargo clippy --locked --manifest-path rust/Cargo.toml --workspace --all-targets -- -D warnings
cargo test --locked --manifest-path rust/Cargo.toml --workspace

mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -DrustConformance=true -DtokenStreamBench=true \
  -Dtinyexpression.classes=/absolute/path/to/tinyexpression/target/classes \
  -Dtinyexpression.grammar=/absolute/path/to/tinyexpression/tools/tinyexpression-p4-lsp-vscode/grammar/tinyexpression-p4.ubnf
```

素の`mvn test`はRust連携をskipする。`-DrustConformance=true`だけでもTinyの外部実装・固定文法とtoken stream測定には別の引数が必要である。
Surefireの`Tests run / Failures / Errors / Skipped`を確認し、対象のskipがあればその検証は未完了とする。
JDK 17を選択し、CIと同じ専用artifactの全体packageと、今回の変更に対するRust連携を分けて実行する。最初のcommandではRust opt-inのskipがあるため、後者のskipなしの結果も確認する。

```sh
mvn -B -pl unlaxer-common-jdk17,unlaxer-dsl-jdk17 -am clean package -Dgpg.skip=true
mvn -B -pl unlaxer-common-jdk17,unlaxer-dsl-jdk17 -am test \
  -Dtest=GraphemeTest,GraphemeConformanceTest,RegexTokenParserTest,ParseDepthLimitTest,JavaParseDepthLimitTest,ClassicLspProtocolConformanceTest,ClassicDapProtocolConformanceTest,RustUbnfFrontendConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

本番bridgeの別固定revisionと追加手順は[既存の検証文書](tinyexpression-production-bridge.md)に従う。

## 2026-10-16の検証記録

実装revisionは `79eb4926701acd0a5a02c7756c8fd334fff9a297`（PR #520でmasterへ統合）。
その後の変更は検証の期待値・診断表示・文書・CI対象の更新で、runtime/generatorの変更はない。

| 検証 | 結果 |
|---|---|
| Java 21.0.9、Rust/Tiny/bench連携を有効にした全体実行 | common 784件成功、DSL 1,234件中1件が旧launcher期待値で不一致。error・skip 0。下記の対象再検証で更新後を確認 |
| Java 21で期待値・診断を更新した3クラスを再コンパイル・実行 | `LSPLauncherGeneratorTest`, `ParseEqualityGoldenTest`, `ZeroWidthSourceRuntimeTest` の8件成功、skip 0 |
| Java 17.0.20.1の専用artifact `clean package` | common 784件、DSL 1,234件、失敗・error 0。DSLの82件は既定設定のopt-in skip |
| Java 17の変更関連8クラス、Rust連携有効 | 19件成功、skip 0。両jarのclass major 61も確認 |
| Rust 1.85 workspace | 357件成功、失敗・ignored 0。fmt / Clippyも成功 |
| release CLI / example | PATHを空にして書記素文法の生成・`--check`成功。release exampleの `if(0,neg(2*3),4+5)` は9 |
| Tiny本番bridge | Tiny 125件と既存IDE 39件成功、skip 0。Java/Rustのstrict 177観測、partial 29観測がそれぞれ一致 |
| Tiny公開P4 facade | UBNFC / CLASSIC各10式成功 |

Tiny本番bridgeは `f86ce8a5ab0ab7d23fdba2cd477187df8aa7607c` と今回の3.3.0-SNAPSHOTを隔離したMaven repositoryで組み合わせた。
Java 17の全体再検証中、一度だけテスト内の動的javacが失敗した。単独2件と全体packageの再実行では再現せず、原因は未確定。
コンパイル完了後に診断を取得するようテストを修正した。再現しないことをruntimeの修正実績には数えない。

3.3.0公開前に、別担当が `83dc40d4` / JDK 17.0.20.1 で追加監査した。
直前の関連テスト、同一JVM内の反復、fresh JVM 3個を含む134件はすべて成功・skip 0。
対象の動的javacは100回成功し、12生成ソースのSHA-256は24反復・4 JVM間で一致した。
共有状態・class loader混入の再現根拠は見つからなかった。単発失敗の原因は未確定のまま記録し、追加の製品コード修正は行っていない。

上記のローカル監査時点ではGitHub Actionsはrunner待ちだった。以下の正式公開元のCI結果とは区別する。

## 3.3.0の正式公開検証

公開元は `567ef6bf1ac06a1d24f418880c2a150ea554b465`、タグは `v3.3.0`。
このcommitの13 workflows / 18 jobsはすべて成功した。公開後の台帳・文書変更を、この検証済みsourceと混同しない。
[全job URL・配布ファイルのSHA-256・署名者・consumer結果](../release/3.3.0-verification.json)を記録した。

| 検証 | 結果 |
|---|---|
| 公開元CI | Java 21 / 17、Error Prone / SpotBugs、Rust 1.85 fmt / Clippy / 357 tests、両host適合性、ブラウザ・VSIX・Pagesを含む18 jobs成功 |
| 現行依存の下流確認 | #530でproperty参照の旧版残留を修正。Tiny本体・IDEのcommon/dslが実際に3.3.0を解決することを確認 |
| 本番bridge | 3.3.0でTiny 125件＋IDE 39件、skip 0。Java/Rust各strict 177＋partial 29観測が独立期待値と一致、両facade各10式成功 |
| Central実配信 | 親POM＋Java 21 / 17の4座標、計17ファイル。署名・checksum・POM・class majorを検証し、全SHA-256が送信bundleと一致 |
| 新規利用者 | 通常CentralからJDKごとに空のrepositoryへ取得。ローカルseedなし。Java 21 / 17各179項目、計358項目成功 |
| GitHub配布物 | 公開した5assetsを再取得して送信元・server digestを照合。VSIXとRust archive 2個の3配布物は署名付きSHA256SUMSとも一致。取得VSIXの実VS Code smokeと、展開Rustの空PATH生成・check・式9も成功 |

共通CIはcommon 8件、DSL 144件で失敗・error 0、DSLの実Tiny文法を必要とする2件はskipだった。
`RustPortabilityConformanceTest`は下流で実Tiny文法を指定し3件skip 0で補完した。
`RustCatalogConformanceTest.realTinyGrammar`は今回CIでは再実行しておらず、先のローカル全体検証の結果として扱う。

Central deployment `62ded256-acc9-44a0-b253-8c1b4e3e27de` はPUBLISHED。
正規URLのGET/HEADと、新規Maven consumerによる取得まで確認した。
公開ファイルのLast-Modifiedは2026-10-16 03:52:51–52 UTC。公開直後の404キャッシュは公開失敗と混同せず、再送せずに反映を確認した。
Rustの配布バイナリはLinux x86_64 GNU向けでGLIBC_2.34とlibgcc_s.so.1を必要とする。MIT・Unicode・依存noticeを同梱し、crateのcrates.io公開は行っていない。

## 対応範囲と適用時の条件

[Rust full-spec対応表](../rust/FULL-SPEC.md)と[IDE棚卸し](lsp-dap-ide-gap-inventory.md)を併読する。
Rust増分解析、一部token/annotation、LSP cancellation・semantic tokens・rename/format transportはこの基準で新しく対応した機能ではない。
DAPはAST/token inspectionであり、live evaluatorの停止・再開ではない。
数値型・変換・診断にはbackend固有の契約がある。

資源制限は[Rust runtimeの説明](../rust/README.md)と[Javaの解析上限API](java-parser-limits.md)を参照する。Rustの`resource_limit`・Javaの`ParseLimitException`が発生したcontextは再利用せず、新しいcontextを作る。通常の構文不一致のrollbackとは区別する。利用者callback内部の任意コードや極端に小さいthread stackを隔離するsandboxではない。
配布・公開済みversionと、ここで検証したsource revisionを区別する。CIの待機中・未実行を成功と表記せず、導入先では使用文法と最大入力の負荷検証を追加する。

## 3.3.0公開後の互換拡張

開発版の [#533](https://github.com/opaopa6969/unlaxer-parser/issues/533) は、
[Unicode17固定のCI token](case-insensitive-tokens.md)をJava/Rustへ追加する。
手書きJava parserのJDK依存比較は維持し、生成CIは同じ固定表を使用する。
監査で再現したJavaのFIRST誤判定（`k`と`K`が診断policyによって不一致になる）も修正する。
この追加は上記3.3.0配布記録の検証対象には含まれず、公開済み3.3.0には入っていない。
新しい共通fixture、Java17/21の生成parser検証、Unicode表の再生成checkを継続CIへ追加する。
