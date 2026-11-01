# Java / Rust Classic の本番品質基準

対象は **3.3.0** の、対応表に記載した Classic の機能である。公開検証は [#522](https://github.com/opaopa6969/unlaxer-parser/issues/522) と[配布記録](../release/3.3.0-verification.json)に保存した。
Maven Central の 3.2.0 に以下の修正が含まれるという意味ではない。
全 Java 文法との互換性、未対応 IDE 機能、任意の負荷に対する性能は別の条件である。

## 開発版の品質基準

3.3.0公開後のparity作業は親[#111](https://github.com/opaopa6969/unlaxer-parser/issues/111)で継続している。
開発版はJava21 / Rust1.85を共通検証の基準とし、Java17固有の互換維持を新機能の制約にしない。
以下のJava17試験と配布記録は公開済み3.3.0の履歴であり、開発版の全機能をJava17で保証するものではない。

現行の対応範囲は[full-spec表](../rust/FULL-SPEC.md)と各機能の共通fixtureで確認する。
全体の完了にはJava/Rust両方の実装、生成consumerのcompile/parse/map/eval、原文・位置・失敗後状態の照合、
対象opt-in試験のskipなし実行、CI成功、各PRのmergeが必要である。
ローカルの部分的な成功、待機中のCI、openのPRを全体完了の根拠にしない。

### 必須 opt-in の実行証跡（#719）

CI の check が緑、または `-Dtest` にクラスがあるだけでは実行済みとしない。
[job 別の契約](../.github/optin-test-contracts.json)に必須クラス・メソッド・有効化 property を記録し、
[verifier](../scripts/verify-optin-tests.py) が実 Surefire XML を照合する。
対象 XML / メソッドの欠落、ゼロ実行、skip / failure / error、失敗後の再試行、
カウンターと testcase の不一致、必須 property の無効化は失敗にする。
クラス単位の契約でも testcase が 1 件以上あり、その全件が成功する必要がある。

| job / 契約 | 必須条件・証跡 |
|---|---|
| `maven.yml:rust-conformance` / `cross-language-core`, `cross-language-cli` | Java21 / Rust1.85、`rustConformance=true`、`tokenStreamBench=true`、固定 Tiny 文法。元の `cross-language` 契約を重複・欠落なしで2分割し、各 shard で必須メソッドと skip 0 を検査 |
| core shard / `tiny-catalog` | `RustCatalogConformanceTest.realTinyGrammarAdvancesPastCatalogToTheSameGenerationFrontier` を含む全件。下流と同じ Tiny `7d7bd1cbfab1bd9548ba53c73cc50502f0437c67` の実 grammar を指定し、XML・frontier・revision を `tiny-catalog-conformance` artifact に保存 |
| `maven.yml:downstream` | `downstream-p4-smoke`（意味 oracle）、`downstream-node-alias`（原文保存）、`downstream-tokens`（実 Tiny クラスと両 Rust generator の意味・位置）、`downstream-portability`（実 grammar）、`downstream-ide-smoke`（LSP）の各実行を直後に検査 |
| `maven.yml:build-jdk17` / `jdk17-parity` | 明示的な Unicode / frontend の 3 クラスだけを検査。通常 package の opt-in skip は対象外 |
| `assertion-contracts`, `shared-language-calls`, `embedded-grammars`, `language-profiles`, `language-queries`, `external-language-providers`, `analysis-pipeline` | 各 workflow の必須クラスと `rustConformance=true` を検査 |
| `source-maps`, `source-edits` | Java 側の位置・編集契約を検査。Rust 側は既存の Cargo 共通 fixture で検証 |

実 Tiny Catalog は共通 job で grammar を渡して実行する。下流 Portability の成功を Catalog の代用にしない。
Catalog の frontier 比較は、それ以降の未対応機能や Tiny 全体の実行成功を保証するものではない。
`tokenStreamBench` は既存の測定テストの実行確認であり、性能改善・新しい性能合否基準は追加しない。

各 Maven 実行の前に `--prepare` でその契約が検査する古い XML を消す。
Maven が成功した後に検査し、対象 XML と `result.json` を
`target/optin-evidence/<契約名>/` に保存して artifact 化する。
verifier が失敗しても証跡はアップロードし、Maven 未実行・失敗時の XML 欠落ノイズを避ける。
別の Maven 実行が XML を上書きする前に検査・保存する。
対象の追加・削除・改名時には契約も更新する。workflow の長い `-Dtest` 一覧とは別ファイルなので、
他の機能 PR による一覧の追加と競合しにくい。`WorkflowTest` は `-Dtest` と契約を双方向に照合する。

#954 の分割では `cross-language.required` を共通の正本として保持する。
クラス追加時は正本、`shards` のどちらか一方、workflow の同じ shard の `matrix.tests` を更新する。
メソッド・property・report path は正本から継承し、クラスの重複・欠落・未登録は prepare 時点でも失敗する。
XML は `optin-cross-language-core` / `optin-cross-language-cli`、生成証跡は
`rust-conformance-core` / `rust-conformance-cli` artifact の和で確認する。
両 shard は独立した Maven repository と Cargo build を使用し、ハーネスの並列度は増やさない。
matrix は `max-parallel: 2` / `fail-fast: false`、各 job の timeout は75分（#1035）。
2026-10-31 の直近5件の master 成功 run（41163126651、41182366451、41198835881、
41228434097、41233307704）は core 32.9〜35.7分、cli 37.3〜39.8分だった。
高負荷時の run 41229276882 / 41234848629 の attempt 1 では、それぞれ core / cli が
50分で打ち切られた。同時期の cli は49.22分、core の再実行は47.65分で完了しており、
75分はこれら完了実測の1.5倍以上にもなる。打ち切り値は完了時間ではなく下限として扱う。
既存2 shard の配分と self-hosted runner を保ち、`PackageJsonAcceptanceConformanceTest` の
Java CLI 1732呼出しを既存 `CodegenBatchHost` の1 JVMに集約する。Rust CLI 1319呼出しと
全481 fixture の検査は維持し、両言語を直列に実行する。
Java の credentials は1つのテスト専用ファイルの全内容をケースごとに置き換え、各呼出しで
製品の resolver に読み直させる。入力・出力ディレクトリはケースごとに分離する。resolve は既存 snapshot の
有無ごと・各 host の直前に lock/cache を復元する。終了コード・診断・生成 bytes・通信回数・失敗時の不変性は集約前と同じ
独立期待値で照合する。`scripts/ubnf_package_json.py --unbatched` で従来のプロセス単位でも比較できる。
batch host は `CodegenMain.run` を呼ぶため、`main` の `System.exit`、プロセスごとの
`UBNF_CREDENTIALS_FILE`、cwd は通らない。そこで既定の実行でも代表5ケース（exit 0/3/4、
HTTPS の通信回数、ケースごとの credentials の拒否）は Java CLI を呼出しごとに JVM を起動して
再実行する。同じ独立期待値に加え、exit・`lastError`・stderr 全文・生成 hash が batch の記録と
一致することを確かめる（`target/package-json-cli-process.json`）。
`target/package-json-cli.json` の各操作の `seconds` / `execution` とケース全体の `seconds` で計測する。
テスト内の上限は1操作30秒・スクリプト全体1000秒・native build 180秒を維持し、
計測でそれぞれ実測の3倍以上の余裕があることを確認する。
集約 job `rust` は元の check 名 `Rust backend and Java conformance` を保ち、
どちらかが失敗・cancel・skip なら成功にしない。branch protection の設定は変更しない。

`assertion-contracts` では契約外も含め全 XML を検査し、従来の全 skip 禁止範囲を維持する。
実 Tiny Portability のメソッドは downstream だけで実行し、共通 job は同クラスの残り2メソッドを選ぶ。

Python unittest の CI 実行は `scripts/run-required-unittests.py` に統一し、skip とゼロ実行も失敗にする。
`skipUnless`（node / Maven / tail の plugin 不足）、`skipTest`、class setup の skip も対象。
全 workflow を横断した結果、既存の条件付き skip は Maven repository テストの3クラスのみだった。
ローカルで各テストを直接実行する場合の通常の unittest skip は維持する。

Surefire を使わない `tiny-classic-oracle.yml:semantic-oracle` は既存の
12 semantic configurations / 216 samples と Java・両 Rust の独立期待値検査を維持する。
packaged IDE / VSIX、本番 bridge の JSON/TSV 検査も引き続き別の必須証跡であり、
この XML verifier の成功で代替しない。
素の `mvn test` や公開済み 3.3.0 の JDK17 履歴には全 skip 禁止を適用しない。

ローカルで同じ対象を実行する場合は、repository ルートで次のように検査する。
Maven は必要なキャッシュを用意した上で `-o` を使用できる。

```sh
python3 scripts/tests/test_verify_optin_tests.py
python3 scripts/verify-optin-tests.py cross-language --prepare
# CI と同じ -Dtest と、rustConformance / tokenStreamBench / tinyexpression.grammar を指定して実行
python3 scripts/verify-optin-tests.py cross-language
python3 scripts/verify-optin-tests.py tiny-catalog
```

2026-10-19の統合記録: 積み上げた#570〜#691のPRを下から順にmergeし、masterは`4e8bd971`（#682のmerge）になった。
そのtreeは`fe648700`と同一で、同revisionのCI 18 checkがすべて成功した（Rust backend and Java conformance、Downstream smoke (tinyexpression)、JDK17 artifacts、VSIX、packaged IDE、各contract系）。
途中のPRは同じ内容を先端に含むため、先端のCIで代表させた。
これは統合済み範囲のCI成功記録であり、両版で位置付き拒否するnestedの拡張（#693〜#697）や#197の性能deadlineの完了を意味しない。

[REGEX廃止](regex-retirement.md)はユーザー指定の開発版変更であり、旧宣言には移行診断を返す。
後段の#505は3.3.0の不具合修正履歴であり、今後Java Pattern互換エンジンを開発する計画ではない。
公開済み成果物・署名・配布記録は保持する。

## 3.3.0の受け入れ条件

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
開発版ではREGEX DSLとJava RegexTokenParser APIを廃止し、[宣言的字句式への移行](regex-retirement.md)を採用した (#612)。公開済み3.3.0と#505の歴史的結果は変更しない。

## 位置・文字の契約

既存のparser cursorとCST/AST spanはCP単位のままである。Javaの`char`はUTF-16 code unit、Rustの`char`はUnicode scalarなので、跨言語APIでは「char位置」という曖昧な名前を使わない。

`👨‍👩‍👧‍👦`は7CP・11 UTF-16 code units・25 UTF-8 bytes・1 extended grapheme clusterである。
graphemeは表示幅ではなく、フォント・端末の列数を計算しない。Unicode正規化も行わない。
位置変換表は元snapshotに結び付く。編集後は新snapshotで作り直し、同じURIでもversion/contentが違う表を流用しない。
Gへのstrict変換では書記素の途中を受理せず、丸めが必要な用途だけ明示的なfloor/ceilingを使う。CP/UTF-16/UTF-8間の変換は従来どおりscalar境界を扱う。
APIとコード例は[書記素座標の説明](grapheme-coordinates.md)を参照する。長さの変換には開始位置が必要である。`ab`も`e\u0301`も2CPだが、それぞれ2Gと1Gなので、範囲の両端を変換して差を取る。

## 開発版の再現手順（Java21）

Linux x86_64、JDK 21、Rust 1.85、Maven、Node/npm、Chromiumを使用する。
ブラウザ/provider検証には先に以下を実行する。

```sh
cd unlaxer-dsl/ubnf-vscode
npm ci --ignore-scripts
npx playwright install chromium
```

固定した開発revisionの独立checkoutのルートで実行する。`tinyexpression` はCIと同じ `7d7bd1cbfab1bd9548ba53c73cc50502f0437c67` の独立checkoutを用意し、現在のunlaxerを指定してcompileしておく。token・移植性のoracleはこの固定revisionを使う。p4-smokeだけはClassic既定AST選択で全preferred候補を列挙しない `420757a8ab94ae1bed6dbd7cea2743efb8840bbd`（tinyexpression #261）で実行する。固定revisionではbounded public mapperが `complex-x64.tiny` を `E-MAPPING-LIMIT: preferred candidates` で拒否するためである。既存checkoutの未commit変更を上書きしない。公開版と同じversion名の開発buildを混同しないよう、専用のMaven repositoryでinstall・依存解決する。このlocal installは公開操作ではない。CIでも全Maven jobがrun・attempt・job単位の空repositoryを使う（[CIの方式](../.github/actions/maven-job-repository/README.md)、#718）。

```sh
parity_m2=$(mktemp -d)
mvn -B -Dmaven.repo.local="$parity_m2" -pl unlaxer-common,unlaxer-dsl -am install -DskipTests -Dgpg.skip=true
mvn -B -Dmaven.repo.local="$parity_m2" -f /absolute/path/to/tinyexpression/pom.xml compile \
  -Dunlaxer.version=3.3.0 -Dtinyexpression.skipRailroad=true

cargo fmt --all --manifest-path rust/Cargo.toml --check
cargo clippy --locked --manifest-path rust/Cargo.toml --workspace --all-targets -- -D warnings
cargo test --locked --manifest-path rust/Cargo.toml --workspace

mvn -B -Dmaven.repo.local="$parity_m2" -pl unlaxer-common,unlaxer-dsl -am test \
  -DrustConformance=true -DtokenStreamBench=true \
  -Dtinyexpression.classes=/absolute/path/to/tinyexpression/target/classes \
  -Dtinyexpression.grammar=/absolute/path/to/tinyexpression/tools/tinyexpression-p4-lsp-vscode/grammar/tinyexpression-p4.ubnf
```

素の`mvn test`はRust連携をskipする。`-DrustConformance=true`だけでもTinyの外部実装・固定文法とtoken stream測定には別の引数が必要である。
Surefireの`Tests run / Failures / Errors / Skipped`を確認し、対象のskipがあればその検証は未完了とする。

### 公開済み3.3.0のJava17検証を再現する場合

以下は **tag `v3.3.0` の独立checkout専用** の手順であり、開発版のJava17対応を追加要求するものではない。
そのcheckoutへ移動し、JDK17を選択して専用artifactの全体packageと関連Rust連携を分けて実行する。
最初のcommandではRust opt-inのskipがあるため、後者のskipなしの結果も確認する。
`RegexTokenParserTest`は当時のAPIの検証であり、REGEXを廃止した開発版では実行しない。

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

### CI の一時領域（#1045）

全 workflow は各 job の最初の step で `TMPDIR` / `TMP` / `TEMP` を `${{ runner.temp }}` に向け、
`GITHUB_ENV` で後続 action / step へ引き継ぐ（runner context は job env では使えない）。
checkout・toolchain setup・Python `tempfile`・shell `mktemp`・cargo/rustc・Node の
`os.tmpdir()` も runner の work disk を使う。build 成果物の `target/`、npm/cargo の
依存 cache、job 専用 Maven repository の既存の配置はそのままとする。
Maven 本体には `MAVEN_OPTS` で `java.io.tmpdir` と `unlaxer.test.tmpdir` を渡し、親 POM の
opt-in profile が Surefire の `argLine` へ起動前に渡す。JDK が一時パスを早期に cache
するため `systemPropertyVariables` だけでは不十分。既存の preview 引数は維持し、
追加の `unlaxer.test.jvmArgs` を連結する（既存の JaCoCo / `@{argLine}` 設定はない）。
property 未指定のローカル実行では OS 既定値を維持する。

JVM property は子 JVM に自動継承されないため、DSL のテストは `TestProcesses.builder` で
`java` / `javac` へ明示的に渡す。Python の Java 起動には `java_tmp.java_command` を使い、
さらに起動する provider のテスト用 command にも渡す。native fixture が実行ファイルパスだけを
受け取る箇所は `scripts/ci/java-tmpdir/bin/java` を渡す。`JAVA_TOOL_OPTIONS` / `_JAVA_OPTIONS` /
`JDK_JAVA_OPTIONS` は stderr の launcher notice が conformance を壊すため使わない。
downstream の固定 POM は変更せず、Surefire の `jvm` に同 launcher を指定して既存の
`argLine`（preview / add-opens）を維持する。
静的解析の forked javac は `-J-Djava.io.tmpdir`、SpotBugs は `jvmArgs` で Maven の一時領域を引き継ぐ。
Javadoc は `MAVEN_OPTS` の `additionalJOption` から `-J-Djava.io.tmpdir` を渡すため、
親 POM を継承しない固定 downstream の package / install も対象になる。
fixture が意図的に指定する `java.io.tmpdir` は後置し、その失敗条件を優先する。

検証は JDK17/21 の関係 conformance と `CiTempDirectoryTest`、Python tests、各プロセスの
`mkdir` syscall 記録で行う。共有 `/tmp` の全体差分は他 job の作成を誤検知するため gate に
しない。merge 後の master CI 3連続成功を確認するまで #1045 は close しない。
