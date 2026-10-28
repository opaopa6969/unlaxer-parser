# feature / lexical 診断の共通契約（#910）

Java の参照 revision は `8ea0310853453d23899f069f81017c5b375e4558`（PR #909 merge）。
原則として native を次の Java 契約へ合わせる。#916 では例外で中断していた Java の check だけを修正し、既存起動経路と generate の検査を維持する。

- `cases.json`: 手書き31文法。LF/CRLF ごとの **check JSON 全体**、generate の先頭エラー、共有 feature 検査の全診断を固定する。終了コードは成功0、不正文法3。期待値は `UBNFFeatures.validate`、`PortabilityCheck.scanGrammar`、`LexicalCompiler` の契約と入力上の CP 位置から作り、Java-host の実 CLI で照合した。テスト中に Java 出力から期待値を書き換えない。
- VERSION / UNKNOWN / DUPLICATE、block 値、重複した未知値、診断順序、非BMP、adapter/contract/whitespace/comment/未知setting/未定義規則との同居を含む。lexical は duplicate、version、undefined、cycle、unbound SAME_AS、nullable repeat、128段・4096ノード上限の全メッセージを含む。
- `modules.json`: import 先に8種類の字句エラーを配置し、LF/CRLFで check の全JSON・終了コード・generate の先頭エラーと無出力を両hostで検査する。
- `lexical-contexts.json`: `@lexicalContext` を持つ文法に5種類の字句エラー（undefined・duplicate・cycle・unbound SAME_AS・nullable repeat）を置いた5文法と、257 terminal の `@lexicalContext`（`E-LEXICAL-CONTEXT-LIMIT`）に字句エラーが同居する1文法の計6文法。native は lowering 前段の `lexical_contexts::validate` で字句エラーを返すので、そこでも Java の `LexicalContexts.terminals` と同じ `E-LEXICAL:` を付け、Java と同じく字句コンパイルを terminal 数の上限より先に行う（#916）。LF/CRLF で両 host の check JSON 全体・終了コード3、generate の先頭エラー・終了コード・無出力と、validator の `E-LEXICAL` 行を比べる（#916）。check の独立期待値は `structure: failed` と grammar 全体の `P-STRUCTURE` 1件。span は入力の CP 数から固定し、実行結果から生成しない。
- `whitespace.json` (#923): 手書きの字句定義・設定・規則を組み合わせた168文法（5種類の字句エラー＋正常系 × 設定3種／規則注釈4種 × `@comment` 有無 × `@lexicalContext` 有無）と、追加の4文法（宣言 token `WS` 自体の字句エラーを設定・規則注釈で参照、設定＋3種の規則注釈が同居する字句エラー、未知名 `foo`＋字句エラー）の計172文法。設定は `javaStyle` / `none` / 非nullableな宣言 token `WS`、規則注釈はさらに引数なし（既定 `javaStyle`）を含む。参照は `0c150b4e`（#922 merge）の Java `PortabilityCheck.whitespaceStyle` / `GlobalComment.problems`。字句コンパイル失敗なら組込み名でも解決失敗とし、値／注釈の CP span・元の subject を持つ `P-WHITESPACE`、`structure: blocked`、終了3を固定する。`@comment` は字句エラー時に `E-COMMENT-WHITESPACE` を追加しない。正常系28文法では従来の受理と、宣言 token の global whitespace＋comment の拒否を維持する。LF/CRLF 計344入力の全 check JSON・終了コード・generate の先頭エラー／終了コード、成功時の全5ファイルを両 host で比べる。期待値は手書き契約と入力上の文字範囲から作り、CLI/AST の観測から生成しない。`javaFirst` は Java が comment の subject と code を分けて表示する既存の形式を固定する（native の `first` と同じ診断）。
- `ubnf-version.json` (#918): 手書き断片を組み合わせた109文法。v0/v3/V1/V2/V3/任意識別子、同値・異値・不正値の重複、block、非BMP、feature/adapter/字句5種/未定義規則/mapping/whitespaceとの同居をLF/CRLFで固定する。参照は `c2e0195e`（#925 merge）のJava。checkの文字列値は構造検査へ委ね、単独ならgrammar全体の `P-STRUCTURE` / `failed` / 終了3、block値は設定範囲の `P-SETTING` / `blocked`。他のinventory診断による構造検査の抑止を保つ。生成の先頭messageは `@ubnf must be format version v1 or v2` または `duplicate global @ubnf setting`、Javaのcodeは `E-UBNF-VERSION[-DUPLICATE]`。件数を先に検査するので不正値＋重複でもDUPLICATEを優先する。字句・adapter・feature・全体whitespaceの診断は先、規則のwhitespace・mapping・未定義規則は後。Java `RustGenerateCommand` は共通検査前に値が厳密に `none` の全体whitespace設定を除くので、`none`＋`javaStyle` や `none` 2回ならubnfが先、`NONE`＋`javaStyle` や `javaStyle` 2回ならwhitespace重複が先となる。正しいubnfは従来のlowering検査を維持する。`javaFirst` は既存CLIのmessage/codeの表示形式、`firstCode` はJavaの先頭code、`ubnf` は両validator/authoring用の独立した詳細診断を固定する。期待値は契約と入力上のCP範囲から作り、Javaの実測と照合した。両hostで全check JSON・終了コード、generate先頭エラー・終了コード・失敗時無出力、正常3文法の全5生成ファイルを比較する。
- `authoring.json`: 先頭12文法を LF/CRLF で展開した24件。Java/Rust の実 stdio に同じ入力を送り、message/hint/severity/range とキーの有無を含む全 JSON を比較する。既存 `ubnf-authoring-lsp/diagnostics.json` の76文法は変更しない。既存103シナリオに追加して baseline は127件となる。

| 利用経路 | 契約 |
|---|---|
| `adapters::feature_diagnostics` / Java `UBNFFeatures.validate` | 非v2なら値の形にかかわらず VERSION。VERSION/UNKNOWN の subject は `feature`、DUPLICATE は宣言値。宣言順を維持 |
| check の portability inventory | Java の `scanGrammar` と同じく feature の意味検査をしない。文字列値は lowering へ進み、feature だけが不正なら grammar 全体を指す `P-STRUCTURE` / `failed`。block 値は `P-SETTING` / `blocked`。別の inventory エラーがあれば構造検査は実行されない |
| check の lexical-context inventory | Java `LexicalContexts.problems` は `E-LEXICAL:` を構造検査へ委ねる。`E-LEXICAL-CONTEXT-*` の code・subject・元位置は維持し、その他の例外は従来どおり再送出。別の inventory エラーがあれば構造検査を抑止する既存規則も維持 |
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
CI の必須実行契約と `rust-feature-lexical-diagnostics.tsv` の653行検査で、未実行を成功扱いにしない。

範囲外として分けた差: lowering 前段検査（`@typeof`・`@lexicalContext` 等）が字句・adapter・contract・feature より先に出る順序差（#917）。`@ubnf` の不正値・重複でも、native の前段検査（`@typeof`・bounded・precedence overflow・`@commonField`・`@enum`・`@eval`）が先に出る差は #917、名前述語が後に出る差は #858、左再帰を native が後段で検出する差は #930、複数 grammar で Java が全 grammar の validator を先に回す差は #931 で追跡し、`ubnf-version.json` には入れない。

#918ではnativeだけが返していた不正な文字列値の位置付き `P-SETTING` を意図的に減らす。Javaのinventoryと構造検査の分担を採り、Java実装・既存のauthoring詳細診断・正しいv1/v2の結果を維持する。
