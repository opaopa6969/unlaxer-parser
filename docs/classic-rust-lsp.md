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
    std::process::exit(generated::lsp::serve_stdio_status()?);
}
```

## 通信・文書契約

Content-Length は UTF-8 **bytes**。ASCII/CRLF headers を検証し、header 8 KiB、32行、body 4 MiB
で打ち切る。重複・非数値・過大 length、切れた frame、非 UTF-8 charset は I/O failure として
接続を終了する。長さが正しい壊れた JSON には ParseError (-32700) を返し、次の frame を処理する。
request ID は整数または文字列。未知の request は MethodNotFound (-32601)、notification に response は返さない。

initialize/initialized/shutdown/exit と full document didOpen/didChange/didClose を扱う。
shutdown 後はすべての request を InvalidRequest (-32600) で拒否し、exit 以外の
notification を無視する。Java の生成 launcher は subclass の独自 request もこの境界で保護する。
exit は client が stdin を閉じる前に transport と所有 worker を終了する。
stdio main は shutdown 後の exit を status 0、shutdown 無しの exit と exit 無しの EOF を status 1 にする（#508）。
Java の `launchStdio()` と生成 Rust の `serve_stdio_status()` はこの status を返し、
`System.exit` / `std::process::exit` は main 側が適用する。
Rust の既存 `serve_stdio() -> io::Result<()>` は埋め込み互換の入口として残し、終了コードを適用する main は `serve_stdio_status()` を選ぶ。
独自 backend の場合は `Server::serve_stdio()` 後の `Server::exit_code()` で同じ status を得る。
Java server の `exit()` / `exitStatus()` や `launch(InputStream, OutputStream)` は埋め込み host を終了しない。
後者は完了 future を返し、exit 時に入力を閉じて worker を解放するが、出力の所有権は caller に残す。
既存の `launch()` は非同期の入口を維持する。UBNF VSIX の main は `launchStdio()` の status を適用する。

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

## Semantic tokens full

[#535](https://github.com/opaopa6969/unlaxer-parser/issues/535) adds
`textDocument/semanticTokens/full` to both generated Rust hosts. The default
`Backend::semantic_tokens_legend()` advertises `valid` / `invalid` with no
modifiers, matching the Java generated server. `Backend::semantic_tokens()`
returns an empty successful result by default. Neither default performs
syntax coloring automatically.

Applications override both hooks for nonempty tokens:

```rust
fn semantic_tokens_legend(&self) -> unlaxer_lsp::SemanticTokensLegend {
    unlaxer_lsp::SemanticTokensLegend {
        token_types: vec!["word".into(), "emoji".into()],
        token_modifiers: vec!["declaration".into()],
    }
}
fn semantic_tokens(
    &mut self,
    snapshot: &unlaxer_runtime::source::Snapshot,
) -> Result<Vec<u32>, String> {
    // Example for the complete source "<😀x>!": emoji at UTF-16 column 1,
    // then x at column 3. Real providers compute data from this exact snapshot.
    if snapshot.text == "<😀x>!" {
        Ok(vec![0, 1, 2, 1, 1, 0, 2, 1, 0, 0])
    } else {
        Ok(vec![])
    }
}
```

This hook uses the existing Java `additionalSemanticTokenData(uri, content)`
encoding, **not CP spans**: groups are `[deltaLine, deltaStart, UTF16Length,
tokenTypeIndex, modifierBits]`. Java custom legends use
`configureAdditionalCapabilities`; Rust legends use the public struct above.
The legend selected at initialize validates subsequent data. Only `full: true`
is advertised; range, delta, result IDs and automatic provider installation
are outside this contract. Language profiles currently have no semantic-token
operation, so this hook remains available under a profile just as in Java.

Tokens must be ordered. Zero-length tokens retain the existing Java raw-hook
behavior and do not count as overlapping spans. `initialize` saves the client capabilities
`textDocument.semanticTokens.multilineTokenSupport` and
`overlappingTokenSupport` (missing/false means unsupported). With multiline
support, length may span line terminators and the absolute UTF-16 endpoint
must be a valid document boundary. Without it, length extending past a line
is interpreted at that line's end, as specified by
[LSP 3.17](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_semanticTokens).
The encoded raw length is preserved; the client applies that clipping rule.
Overlapping effective spans require overlapping support. CR/LF/CRLF are line
terminators; endpoints cannot bisect CRLF or a supplementary scalar.
All fields must fit LSP `uinteger` (0 through 2147483647), token type must also
be below 65536, and type/modifier indices must exist in the advertised legend.
Both transports build UTF-16 line/boundary indices in O(source + tokens), so
overlap does not cause repeated scans of source prefixes. Invalid provider
data or explicit provider failure returns InternalError (-32603), preserving
the document and the ability to serve later requests. This differs from an
unopened/closed document or a successful empty provider, which returns
`{"data":[]}`. Java's legacy `null` hook result remains an empty success.

The immutable Rust Snapshot supplies URI, text and accepted version. No token
cache is kept: accepted full-sync changes reach the next hook call, while
stale versions and rejected ranged edits preserve the old source. Close
removes it; reopening may restart the version. Shutdown rejects full requests
with -32600 before invoking hooks. Existing lifecycle behavior is retained:
Both transports reject requests before initialize with -32002; see the
[shared lifecycle and configure migration](classic-lsp-lifecycle.md).

[semantic-tokens.json](fixtures/classic-lsp/semantic-tokens.json) is the shared
30-case oracle for actual nonempty hooks, custom legends, supplementary
characters, CR/LF/CRLF, malformed data and provider failure. It also covers
all four multiline/overlap capability combinations, omitted capabilities,
and token types 65535/65536 against a real 65537-entry custom legend. The Java21
`ClassicSemanticTokensConformanceTest` starts real generated Java and Rust
stdio servers, compares both Rust generation hosts byte for byte, and checks
version transactions, close/reopen, profile selection and shutdown/exit.

## 検証と残る差

[positions.tsv](fixtures/classic-lsp/positions.tsv) の独立期待値18件を Java/Rust の生成 server の実 stdio frame で検証する。
Java/native Rust の6 artifact は一致し、生成 Rust server を実 compile/run する。
[providers.jsonl](fixtures/classic-lsp/providers.jsonl) の6件を Java generated LanguageServer と Rust transport に渡して、
local/foreign definition、hover、completion edits、invalid UTF16、delimiter を共通期待値で照合する。
Rust はさらに typed partial/source/version/range 不一致を検証する。

```sh
CARGO_INCREMENTAL=0 cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-lsp
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ClassicLspProtocolConformanceTest,ClassicSemanticTokensConformanceTest,LSPQueryConsumerTest,LSPNonBmpOffsetTest,LSPProfileInitializationTest,RustUbnfFrontendConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

Rust transport は serial 実行で、parse 中の cancel notification を読み取って割り込む仕組みは未実装。
`$/cancelRequest` は未処理 request を捏造せず、すでに完了した同期 request に対して結果を再送しない。
Java の LSP4J の非同期 cancellation と同等とはしない。
開発版では[同一snapshotの構文cache](incremental-cache.md)をJava/Rustで提供し、編集後は全文解析する。部分CSTの編集再接合、incremental sync、rename/format/code-action の transport は未対応で、能力を広告しない。DAP は [AST / token inspection](classic-rust-dap.md) に対応する。
provider runtime の対応と protocol method の実装は別であり、親 issue は残す。
TinyExpression/ubnfc の LSP や Java サーバー起動で Classic Rust LSP の完了を代用しない。
root retry と性能 freeze の方針は変更しない。

Typed language diagnostics use `Backend::language_queries` and the shared
`Provider::diagnostics` API. The server publishes each mapped diagnostic under
its owning URI and exact UTF-16 snapshot range, preserving compiler metadata in
`Diagnostic.data`. Open/change/save refresh the source host's contribution;
close removes only that host, preserving other formulas' shared dependency
diagnostics. Invalid/stale project bindings are rejected and logged. See
`docs/language-query-forwarding.md` for the immutable Project contract and the
real-javac Java/Rust lifecycle oracle.
