# Classic LSP の構文診断位置

生成 Java / Rust classic LSP は、完全な文書を検証し、解析失敗または未消費の末尾入力が
ある場合に `ULX-PARSE-001` を表示する。成功した完全入力には構文診断を出さない。

## 位置の選択

- 解析失敗は、その解析で記録された最遠失敗位置を使用する。
- 文法の root が成功して末尾入力が残った場合、未消費入力の開始と最遠失敗位置の
  遅い方を使用する。選択肢や繰り返しの試行が巻き戻っても、より先で検出した
  構文エラーをエディターへ示す。Rust は `trailing_input` の場合だけ LSP アダプターで
  この選択を行い、同じ最遠失敗の期待候補をメッセージと `data.expectedTokens` に使う。
- Rust の資源上限や名前解決など、構文以外の失敗は既存の primary 位置と期待候補を
  保持する。追加の provider 診断、profile の検証可否、診断コード・severity は変更しない。

これは **LSP の表示契約**である。Rust 公開 `parse_detailed` /
`parse_detailed_shared` の `ParseDiagnostic.offset` / `expected` は引き続き
未消費入力の primary 診断を返し、`farthest` を別に保持する。一般 API の分類や
primary / farthest の意味は変更しない。

位置は原文のコードポイントから UTF-16 の LSP 座標へ変換する。診断範囲は原則として
1 コードポイント、EOF では長さ 0。補助平面文字は UTF-16 で長さ 2、CRLF は途中で
分断せず行終端全体を覆う。`data.offset` と `data.totalLength` も原文の UTF-16 単位。

例えば TinyCalc の `var x set 1;\r\nx + 😀` は両版で
`[line=1, character=4 .. line=1, character=6]` を指す。
`x +` は `[0,3 .. 0,3]` となり、いずれも式の開始候補 `(` を含む期待情報を出す。

## 共通検証と残る表現差

独立した期待値は [`fixtures/classic-lsp/diagnostics.json`](fixtures/classic-lsp/diagnostics.json)
の 20 ケースに置く。TinyCalc 文法から生成した Java / Rust の実 stdio サーバーへ
Content-Length フレームを送り、正常入力、不完全式、EOF、末尾の異物、空入力、
通常の構文失敗、emoji、LF / CRLF の範囲・UTF-16 オフセット・期待 literal を検証する。
同じ試験で Java-host / native Rust-host の生成物も比較する。

期待候補全体の文字列はバックエンド固有である。Java は literal を引用符付きで表示し、
内部パーサー名・空白パーサーの候補を含む。Rust は literal を引用符なしで表示し、
末尾入力では同じ位置の `end of input` 候補を含み得る。共通試験は必要な literal を
引用符だけ正規化して検査し、内部パーサー名やメッセージ全文の一致は契約にしない。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ClassicLspDiagnosticsConformanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DrustConformance=true
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml -p unlaxer-lsp --test diagnostics
```

素の Maven 実行では Java の検証のみ行い、Rust 実通信には `-DrustConformance=true` が必要。
Rust の単体試験は公開 primary / farthest の不変、通常の構文・末尾入力、正常入力、
資源上限の保持も検証する。
