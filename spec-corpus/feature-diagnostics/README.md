# feature / lexical 診断の共通契約（#910）

Java の参照 revision は `8ea0310853453d23899f069f81017c5b375e4558`（PR #909 merge）。
Java の実装・既存起動経路を変更せず、native を次の契約へ合わせる。

- `cases.json`: 手書き31文法。LF/CRLF ごとの **check JSON 全体**、generate の先頭エラー、共有 feature 検査の全診断を固定する。終了コードは成功0、不正文法3。期待値は `UBNFFeatures.validate`、`PortabilityCheck.scanGrammar`、`LexicalCompiler` の契約と入力上の CP 位置から作り、Java-host の実 CLI で照合した。テスト中に Java 出力から期待値を書き換えない。
- VERSION / UNKNOWN / DUPLICATE、block 値、重複した未知値、診断順序、非BMP、adapter/contract/whitespace/comment/未知setting/未定義規則との同居を含む。lexical は duplicate、version、undefined、cycle、unbound SAME_AS、nullable repeat、128段・4096ノード上限の全メッセージを含む。
- `modules.json`: import 先に8種類の字句エラーを配置し、LF/CRLFで check の全JSON・終了コード・generate の先頭エラーと無出力を両hostで検査する。
- `authoring.json`: 先頭12文法を LF/CRLF で展開した24件。Java/Rust の実 stdio に同じ入力を送り、message/hint/severity/range とキーの有無を含む全 JSON を比較する。既存 `ubnf-authoring-lsp/diagnostics.json` の76文法は変更しない。既存103シナリオに追加して baseline は127件となる。

| 利用経路 | 契約 |
|---|---|
| `adapters::feature_diagnostics` / Java `UBNFFeatures.validate` | 非v2なら値の形にかかわらず VERSION。VERSION/UNKNOWN の subject は `feature`、DUPLICATE は宣言値。宣言順を維持 |
| check の portability inventory | Java の `scanGrammar` と同じく feature の意味検査をしない。文字列値は lowering へ進み、feature だけが不正なら grammar 全体を指す `P-STRUCTURE` / `failed`。block 値は `P-SETTING` / `blocked`。別の inventory エラーがあれば構造検査は実行されない |
| generate の lowering | 最初の lexical compile 失敗に `E-LEXICAL: ` を付ける。共有 lexical 関数の返り値は変更しない。feature は共有検査を使う |
| validation / authoring LSP | feature は共有検査を直接使用し、従来の専用 subject/優先順位補正を削除。range は Java と同じ `0:0–0:1`。lexical 専用の prefix 付加経路は保持 |
| modules | feature 診断の有無で拒否する従来経路を維持。subject/code の修正で受理集合は変わらず、`compile_syntax` 失敗には module 読込み入口で Java と同じ `E-LEXICAL:` を付ける |

native の check が以前返した feature 設定位置の `E-FEATURE-*` を減らすのは意図した変更。
Java と同じ inventory → structural lowering の段階分けを採用し、Java の機能は削らない。
generate と LSP の feature 診断は引き続き返す。

実行先は Rust `feature_diagnostics.rs`、Java `RustPortabilityConformanceTest#featureAndLexicalDiagnosticsMatchIndependentOracleAcrossConsumers`、
`UbnfAuthoringProtocolConformanceTest#nativeAndJavaServersShareAuthoringFixtures`。
CLI の Rust process は空 PATH・存在しない JAVA_HOME でも動かす。
成功時は Java-host/native の Rust 全5ファイルを byte 比較し、不正入力では生成先を作らないことも検査する。
CI の必須実行契約と `rust-feature-lexical-diagnostics.tsv` の79行検査で、未実行を成功扱いにしない。
