# Classic Rust LSP

[#451](https://github.com/opaopa6969/unlaxer-parser/issues/451) は Classic の生成 grammar を stdio LSP で使う入口を追加する。
[#111](https://github.com/opaopa6969/unlaxer-parser/issues/111) の full-spec と
[#384](https://github.com/opaopa6969/unlaxer-parser/issues/384) の IDE 統合はこの入口だけでは完了しない。
core `unlaxer-runtime` に JSON 依存を追加せず、transport は `unlaxer-lsp` に分離する。

Java-host と native の両方で `generate --target rust --grammar example.ubnf --output src/generated --lsp`
を指定する。通常の生成は従来どおり5ファイル、`--lsp` は `mod.rs` に LSP module を追加して
6番目の `lsp.rs` を生成する。既存 output 保護と `--check` は同じで、手書きコードを上書きしない。
利用側 Cargo manifest に次の依存を追加する（path は checkout に合わせる）。

```toml
[dependencies]
unlaxer-runtime = { path = "/path/to/unlaxer-parser/rust/unlaxer-runtime" }
unlaxer-lsp = { path = "/path/to/unlaxer-parser/rust/unlaxer-lsp" }
```

```rust
mod generated;
fn main() -> std::io::Result<()> {
    generated::lsp::serve_stdio()
}
```

## 通信・文書契約

Content-Length は UTF-8 **bytes**。ASCII/CRLF headers を検証し、header 8 KiB、32行、body 4 MiB
で打ち切る。重複・非数値・過大 length、切れた frame、非 UTF-8 charset は I/O failure として
接続を終了する。長さが正しい壊れた JSON には ParseError (-32700) を返し、次の frame を処理する。
request ID は整数または文字列。未知の request は MethodNotFound (-32601)、notification に response は返さない。

initialize/initialized/shutdown/exit と full document didOpen/didChange/didClose を扱う。
変更は開いている文書の増加 version と非空 contentChanges が必要で、全 entry が full replacement の場合に
最後の text を適用する。ranged edit、空 changes、古い/負の version、未 open 文書を黙って全置換しない。
Java 生成 LSP も同じ判定を行う。close は保持文書と version を捨て、空 diagnostics を publish する。
Java の既存 ParseResult.errorOffset/consumedLength/totalLength は UTF-16 のまま維持する。
Rust runtime の CP spans は transport の外で UTF-16/LSP Position に変換し、non-BMP と CR/LF/CRLF、
EOF を保持する。サロゲート途中・line 外の completion position は空応答であり、キーワードに戻らない。
syntax diagnostic の end は Unicode scalar 全体を覆い、CRLF の中間を endpoint にしない。
診断の expected/message は各 parser の診断であり、任意文法で文章が完全一致する保証はない。

languageProfile の canonical TSV、grammar/root entry の検査、InvalidParams (-32602)、
experimental.languageProfile の identity は共通 LanguageProfile/Selection を使う。
SUPPORTED/PARTIAL のローカル能力だけを使い、EXTERNAL はホストが実 operation を登録した場合だけ有効にする。
profile ファイルを選ぶだけで provider はインストールされない。VALIDATE 非対応時は空 diagnostics、
COMPLETION/HOVER 非対応時は空/null応答になる。

## typed editor / provider

生成された `backend()` は grammar syntax validation と既存 Java 生成器と同じ keyword completion を提供する。
アプリケーションは `Backend` を実装して `Server::new(backend)` へ渡せる。`complete(snapshot,cursor)` の
cursor と edit spans は CP。返す Completion は元 Snapshot を含み、URI/source/version 不一致や無効な範囲は捨てる。
`editor_completions(snapshot,cursor,prefix_span,result)` は既存 EditorParseResult と retained SemanticModel を利用し、
PARTIAL のまま候補・expected types・status・prefix replacement を返す。strict AST を捏造しない。
prefix_span は言語側が指定する。Rust で Java Character の Unicode identifier category を近似して決めない。

`language_queries(snapshot)` は明示的な immutable region/project/provider binding を返す。
`query_capabilities()` は登録済み operation、`query_parameters()` はその言語の prefix/name などを渡す。
Java の `languageQueries` / `languageQueryCapabilities` / `languageQueryParameters` と同じ境界で、
LanguageQueries::view が ownership、host/project/provider の source/version、閉じた delimiter、edit の逆写像を検証する。
completion は host UTF-16 TextEdit と additionalTextEdits、region/state/version を返す。
hover と definition も実 provider を呼び、別文書の definition はその文書の Snapshot で range を変換する。
未登録時は keyword/typed hook と grammar hover を使う。登録した binding が missing/stale/failed の場合は空応答。

## 検証と残る差

[positions.tsv](fixtures/classic-lsp/positions.tsv) の独立期待値18件を Java/Rust の生成 server の実 stdio frame で検証する。
Java/native Rust の6 artifact は一致し、生成 Rust server を実 compile/run する。
[providers.jsonl](fixtures/classic-lsp/providers.jsonl) の6件を Java generated LanguageServer と Rust transport に渡して、
local/foreign definition、hover、completion edits、invalid UTF16、delimiter を共通期待値で照合する。
Rust はさらに typed partial/source/version/range 不一致を検証する。

```sh
CARGO_INCREMENTAL=0 cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-lsp
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ClassicLspProtocolConformanceTest,LSPQueryConsumerTest,LSPNonBmpOffsetTest,LSPProfileInitializationTest,RustUbnfFrontendConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

Rust transport は serial 実行で、parse 中の cancel notification を読み取って割り込む仕組みは未実装。
`$/cancelRequest` は未処理 request を捏造せず、すでに完了した同期 request に対して結果を再送しない。
Java の LSP4J の非同期 cancellation と同等とはしない。
Rust incremental parse/cache、semanticTokens、rename/format/code-action の transport、DAP は未対応で、能力を広告しない。
provider runtime の対応と protocol method の実装は別であり、親 issue は残す。
TinyExpression/ubnfc の LSP や Java サーバー起動で Classic Rust LSP の完了を代用しない。
root retry と性能 freeze の方針は変更しない。
