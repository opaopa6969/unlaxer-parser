package org.unlaxer.dsl.codegen;

/** Optional, snapshot-checked bridge from generated LSP methods to explicit language providers. */
final class LSPQueryEmitter {
    private LSPQueryEmitter() {}
    static void emit(IndentedWriter w) {
        """
            /** Return a fresh immutable project/region/provider binding for this exact document, or null. */
            protected org.unlaxer.source.LanguageQueries languageQueries(org.unlaxer.source.DocumentSnapshot host) { return null; }
            /** Operations whose providers the host explicitly registers; profile metadata alone cannot enable them. */
            protected java.util.Set<org.unlaxer.source.LanguageRegions.Operation> languageQueryCapabilities() { return java.util.Set.of(); }
            private void configureLanguageQueryCapabilities(ServerCapabilities capabilities) {
                if (languageQueryCapabilities().contains(org.unlaxer.source.LanguageRegions.Operation.DEFINITION)) capabilities.setDefinitionProvider(true);
                var operations = new java.util.TreeMap<String, Object>();
                var transport = java.util.Set.of("VALIDATE", "COMPLETION", "HOVER", "DEFINITION");
                for (var operation : org.unlaxer.source.LanguageRegions.Operation.values()) {
                    if (operation == org.unlaxer.source.LanguageRegions.Operation.PARSE) continue;
                    boolean supported = transport.contains(operation.name());
                    boolean registered = languageQueryCapabilities().contains(operation);
                    boolean allowed = profileAllows(operation.name());
                    operations.put(operation.name(), java.util.Map.of("transport", supported, "providerRegistered", registered,
                        "profileAllowed", allowed, "available", supported && registered && allowed));
                }
                var gson = new com.google.gson.Gson();
                var previous = gson.toJsonTree(capabilities.getExperimental());
                var extensions = previous.isJsonObject() ? previous.getAsJsonObject() : new com.google.gson.JsonObject();
                if (!previous.isJsonNull() && !previous.isJsonObject()) extensions.add("additional", previous);
                extensions.add("languageQueryConsumer", gson.toJsonTree(java.util.Map.of("schemaVersion", 1, "operations", operations)));
                capabilities.setExperimental(extensions);
            }
            private org.unlaxer.source.LanguageQueryView languageQuery(TextDocumentPositionParams params, org.unlaxer.source.LanguageRegions.Operation operation) {
                if (params == null || params.getTextDocument() == null) return null;
                String uri = params.getTextDocument().getUri();
                DocumentState state = documents.get(uri);
                if (state == null) return null;
                long version = documentVersions.getOrDefault(uri, 0L);
                var host = new org.unlaxer.source.DocumentSnapshot(uri, version, state.content());
                try {
                    var queries = languageQueries(host);
                    if (queries == null) return languageQueryCapabilities().isEmpty() ? null : new org.unlaxer.source.LanguageQueryView(host, operation, 0, Set.of(),
                        new org.unlaxer.source.LanguageQueries.Result("", org.unlaxer.source.LanguageRegions.State.UNAVAILABLE, List.of()));
                    if (!profileAllows(operation.name())) return new org.unlaxer.source.LanguageQueryView(host, operation, 0, Set.of(),
                        new org.unlaxer.source.LanguageQueries.Result("", org.unlaxer.source.LanguageRegions.State.UNSUPPORTED, List.of()));
                    Position position = params.getPosition();
                    if (position == null) throw new IllegalArgumentException("missing cursor");
                    int cursor = host.fromLsp(new org.unlaxer.source.DocumentSnapshot.Position(position.getLine(), position.getCharacter()));
                    var view = queries.view(host, queries.project(), cursor, operation, languageQueryParameters(host, cursor, operation));
                    if (documentVersions.getOrDefault(uri, 0L) != version || documents.get(uri) == null || !documents.get(uri).content().equals(host.text()))
                        throw new IllegalArgumentException("stale query response");
                    return view;
                } catch (IllegalArgumentException error) {
                    return new org.unlaxer.source.LanguageQueryView(host, operation, 0, Set.of(),
                        new org.unlaxer.source.LanguageQueries.Result("", org.unlaxer.source.LanguageRegions.State.FAILED, List.of()));
                }
            }
            /** Override when the embedded language uses a different identifier syntax. Coordinates are host code points. */
            protected Map<String, String> languageQueryParameters(org.unlaxer.source.DocumentSnapshot host, int cursor, org.unlaxer.source.LanguageRegions.Operation operation) {
                int offset = host.utf16(cursor), start = offset, end = offset;
                while (start > 0 && Character.isUnicodeIdentifierPart(host.text().codePointBefore(start))) start -= Character.charCount(host.text().codePointBefore(start));
                while (end < host.text().length() && Character.isUnicodeIdentifierPart(host.text().codePointAt(end))) end += Character.charCount(host.text().codePointAt(end));
                return Map.of("prefix", host.text().substring(start, offset), "name", host.text().substring(start, end));
            }
            private static Range queryRange(org.unlaxer.source.DocumentSnapshot snapshot, org.unlaxer.source.DocumentSnapshot.Span span) {
                var start = snapshot.lsp(span.start()); var end = snapshot.lsp(span.end());
                return new Range(new Position(start.line(), start.character()), new Position(end.line(), end.character()));
            }
            private List<CompletionItem> languageCompletionItems(CompletionParams params) {
                var view = languageQuery(params, org.unlaxer.source.LanguageRegions.Operation.COMPLETION);
                if (view == null) return null;
                var items = new ArrayList<CompletionItem>();
                if (view.result().state() != org.unlaxer.source.LanguageRegions.State.COMPLETE && view.result().state() != org.unlaxer.source.LanguageRegions.State.PARTIAL) return items;
                for (var candidate : view.result().items()) {
                    var item = new CompletionItem(candidate.label()); item.setDetail(candidate.detail()); item.setKind(CompletionItemKind.Variable);
                    item.setData(Map.of("uri", view.host().uri(), "version", Long.toString(view.host().version()), "region", view.result().region(), "state", view.result().state().name()));
                    try {
                        var edits = new ArrayList<TextEdit>();
                        for (var edit : candidate.edits()) edits.add(new TextEdit(queryRange(view.host(), edit.span()), edit.replacement()));
                        if (edits.isEmpty()) item.setInsertText("");
                        else { item.setTextEdit(Either.forLeft(edits.get(0))); if (edits.size() > 1) item.setAdditionalTextEdits(edits.subList(1, edits.size())); }
                    } catch (IllegalArgumentException error) { continue; }
                    items.add(item);
                }
                return items;
            }
            private Optional<Hover> languageHover(HoverParams params) {
                var view = languageQuery(params, org.unlaxer.source.LanguageRegions.Operation.HOVER);
                if (view == null) return null;
                if ((view.result().state() != org.unlaxer.source.LanguageRegions.State.COMPLETE && view.result().state() != org.unlaxer.source.LanguageRegions.State.PARTIAL) || view.result().items().isEmpty()) return Optional.empty();
                var content = new MarkupContent("plaintext", view.result().items().stream().map(item -> item.label() + ": " + item.detail()).collect(java.util.stream.Collectors.joining("\\n")));
                return Optional.of(new Hover(content));
            }
            private List<Location> languageDefinitions(DefinitionParams params) {
                var view = languageQuery(params, org.unlaxer.source.LanguageRegions.Operation.DEFINITION);
                if (view == null) return null;
                var locations = new ArrayList<Location>();
                if (view.result().state() != org.unlaxer.source.LanguageRegions.State.COMPLETE && view.result().state() != org.unlaxer.source.LanguageRegions.State.PARTIAL) return locations;
                for (var item : view.result().items()) for (var mapping : item.locations()) {
                    var location = mapping.location();
                    try { locations.add(new Location(location.snapshot().uri(), queryRange(location.snapshot(), location.span()))); }
                    catch (IllegalArgumentException error) { /* No guessed UTF-16 positions. */ }
                }
                return locations;
            }
            """.lines().forEach(w::line);
        w.blankLine();
    }
    static void emitDefinition(IndentedWriter w) {
        """
            @Override
            public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
                if (!server.profileAllows("DEFINITION")) return CompletableFuture.completedFuture(Either.forLeft(List.of()));
                var locations = server.languageDefinitions(params);
                if (locations != null) return CompletableFuture.completedFuture(Either.forLeft(locations));
                var state = server.documents.get(params.getTextDocument().getUri());
                var custom = server.customDefinition(params, state == null ? "" : state.content());
                return CompletableFuture.completedFuture(custom == null ? Either.forLeft(List.of()) : Either.forRight(List.of(custom)));
            }
            """.lines().forEach(w::line);
        w.blankLine();
    }
}
