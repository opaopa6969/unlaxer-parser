# Classic Java/Rust LSP lifecycle

The generated Classic Java server and `unlaxer-lsp::Server` share the following
stdio contract. This applies to protocol requests/notifications and the Java
text-document service; standalone parsing utilities such as `parseDocument`
remain usable without an LSP connection.

| State / input | Behavior |
|---|---|
| Before a successful initialize, ordinary request (including shutdown/configure) | ServerNotInitialized (-32002), without application callbacks |
| Before initialize, notification other than exit | Drop without parsing, retaining a document, or invoking callbacks |
| Invalid initialize parameters | InvalidParams (-32602); remain uninitialized and permit a corrected attempt |
| First successful initialize request | Return capabilities; subsequent ordinary requests become available |
| initialize after success | InvalidRequest (-32600); retain existing configuration and documents |
| initialized notification | Java forwards the first notification after successful initialize once; early/duplicate notifications have no effect. Rust has no corresponding application callback |
| initialize/shutdown/configure sent as notifications | Drop, preserving lifecycle/configuration |
| Known notification-only methods sent as requests after initialize | InvalidRequest (-32600), without terminating the process or invoking notification callbacks |
| shutdown request after initialize | Successful null response; reject all subsequent requests with -32600 and ignore notifications other than exit |
| exit notification | Stop transport without waiting for client EOF; status 0 after shutdown, otherwise 1 |
| EOF without exit | Status 1, including after shutdown |

The boundary follows the
[LSP 3.17 initialize contract](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialize):
initialize is sent once; ordinary pre-initialize requests fail and notifications
other than exit are dropped. The existing behavior allowing ordinary requests
after the initialize result, without additionally waiting for `initialized`,
is retained. Applications must not send additional traffic while initialize
is pending. The request/notification type boundary includes the 19 notification names
described by the Java LSP4J LanguageServer interface. Recognizing those names
for rejection does not implement or advertise optional notebook/file-event
features. Java's endpoint also guards subclass custom request/notification
methods, so a rejected request cannot bypass the boundary through an extension.

## Migration from repeated initialize

Earlier Classic transports accepted repeated initialize as a way to change
language profiles and client semantic-token settings. That use conflicted with
the protocol's single-initialize rule. Use the explicit application extension
`unlaxer/configure` instead:

```json
{"jsonrpc":"2.0","id":2,"method":"unlaxer/configure","params":{"initializationOptions":{"languageProfile":{"tsv":"...","entry":"Root"}}}}
```

It accepts the same parameter shape as initialize and returns InitializeResult
with the resulting capabilities. Java embedding code may call
`configure(InitializeParams)` directly. Rust applications use the corresponding
request through `Server::handle` or stdio. Configure requires a successful
initialize and is rejected after shutdown. It preserves open documents and
versions. Invalid parameters preserve the previous profile, semantic legend
and client settings; Java also restores its catalog resolver and built-in
configuration if configuration construction throws. Arbitrary external effects
of application configuration hooks cannot be undone by the transport.

Configure replaces configuration rather than merging it: omitted
initializationOptions selects the default profile, and omitted semantic-token
client capabilities reset multiline/overlap support to false, matching the
previous repeated-initialize behavior. A caller changing semantic capabilities
must include them again when needed. The caller consumes the returned
capabilities itself. No automatic dynamic registration or notification to
ordinary LSP clients is promised by this extension.

[The shared lifecycle oracle](fixtures/classic-lsp/lifecycle.json) runs through
actual generated Java/Rust Content-Length framed stdio processes in
`ClassicLspLifecycleConformanceTest`. Independent callback totals confirm that
dropped notifications and rejected requests did not parse documents or execute
semantic-token hooks. It covers failed initialization/retry, duplicate
initialize/initialized, notification/request misuse, profile configuration
success/failure, document preservation, shutdown and process exit. Existing
`ClassicLspProtocolConformanceTest` additionally checks unclean exit and EOF,
embedding lifetime, Unicode/CRLF positions and document version transactions.

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ClassicLspLifecycleConformanceTest,ClassicLspProtocolConformanceTest,ClassicSemanticTokensConformanceTest,LSPProfileInitializationTest,LSPQueryConsumerTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```
