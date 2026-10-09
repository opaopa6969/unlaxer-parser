package org.unlaxer.dsl.codegen;

/** Typed region diagnostics, staged per owning host before publishing document notifications. */
final class LSPDiagnosticEmitter {
    private LSPDiagnosticEmitter() {}
    static void emit(IndentedWriter w) {
        """
            private record QueryDiagnosticDocument(org.unlaxer.source.DocumentSnapshot snapshot, List<Diagnostic> diagnostics) {}
            private final Map<String, Map<String, QueryDiagnosticDocument>> languageDiagnosticContributions = new HashMap<>();
            private void publishLanguageDiagnostics(String uri, String content, List<Diagnostic> local) {
                var host = new org.unlaxer.source.DocumentSnapshot(uri, documentVersions.getOrDefault(uri, 0L), content);
                var staged = new LinkedHashMap<String, QueryDiagnosticDocument>();
                staged.put(uri, new QueryDiagnosticDocument(host, new ArrayList<>(local)));
                if (profileAllows("VALIDATE")) try {
                    var queries = languageQueries(host);
                    if (queries != null) {
                        for (var snapshot : queries.project().documents().values()) {
                            var opened = documents.get(snapshot.uri());
                            if (opened != null && (documentVersions.getOrDefault(snapshot.uri(), 0L) != snapshot.version() || !opened.content().equals(snapshot.text())))
                                throw new IllegalArgumentException("stale project document");
                        }
                        for (var result : queries.diagnosticsAll(host, queries.project(), Map.of())) {
                            if (result.state() == org.unlaxer.source.LanguageRegions.State.TIMEOUT || result.state() == org.unlaxer.source.LanguageRegions.State.FAILED)
                                client.logMessage(new MessageParams(MessageType.Warning, "Language diagnostics " + result.state().name() + " for " + result.region()));
                            for (var value : result.diagnostics()) {
                            var related = new ArrayList<DiagnosticRelatedInformation>();
                            for (var mapping : value.locations()) {
                                var location = mapping.location();
                                related.add(new DiagnosticRelatedInformation(new Location(location.snapshot().uri(), queryRange(location.snapshot(), location.span())), value.message()));
                            }
                            for (var mapping : value.locations()) {
                                var location = mapping.location();
                                var diagnostic = new Diagnostic(queryRange(location.snapshot(), location.span()), value.message());
                                diagnostic.setCode(Either.forLeft(value.code())); diagnostic.setSource("unlaxer-language");
                                diagnostic.setSeverity(switch(value.severity()) { case "ERROR" -> DiagnosticSeverity.Error;
                                    case "WARNING", "MANDATORY_WARNING" -> DiagnosticSeverity.Warning;
                                    case "HINT", "HELP", "SUGGESTION" -> DiagnosticSeverity.Hint; default -> DiagnosticSeverity.Information; });
                                if (related.size() > 1) diagnostic.setRelatedInformation(related);
                                diagnostic.setData(Map.of("region", result.region(), "state", result.state().name(), "exact", mapping.exact(),
                                    "uri", location.snapshot().uri(), "version", Long.toString(location.snapshot().version()), "severity", value.severity()));
                                var document = staged.computeIfAbsent(location.snapshot().uri(), ignored -> new QueryDiagnosticDocument(location.snapshot(), new ArrayList<>()));
                                if (!document.snapshot().equals(location.snapshot())) throw new IllegalArgumentException("conflicting diagnostic snapshots");
                                document.diagnostics().add(diagnostic);
                            }
                            }
                        }
                    }
                } catch (IllegalArgumentException error) {
                    staged.clear(); staged.put(uri, new QueryDiagnosticDocument(host, new ArrayList<>(local)));
                    client.logMessage(new MessageParams(MessageType.Warning, "Language diagnostics unavailable: " + error.getMessage()));
                }
                var current = documents.get(uri);
                if (current == null || documentVersions.getOrDefault(uri, 0L) != host.version() || !current.content().equals(content)) return;
                var affected = new java.util.TreeSet<String>(); affected.addAll(staged.keySet());
                var previous = languageDiagnosticContributions.put(uri, staged); if (previous != null) affected.addAll(previous.keySet());
                publishDiagnosticContributions(affected);
            }
            private void clearLanguageDiagnostics(String uri) {
                var affected = new java.util.TreeSet<String>(); affected.add(uri);
                var previous = languageDiagnosticContributions.remove(uri); if (previous != null) affected.addAll(previous.keySet());
                publishDiagnosticContributions(affected);
            }
            private void publishDiagnosticContributions(Set<String> uris) {
                if (client == null) return;
                for (var uri : uris) {
                    var opened = documents.get(uri);
                    org.unlaxer.source.DocumentSnapshot expected = opened == null ? null : new org.unlaxer.source.DocumentSnapshot(uri, documentVersions.getOrDefault(uri, 0L), opened.content());
                    boolean ambiguous = false; var combined = new ArrayList<Diagnostic>();
                    for (var owner : new java.util.TreeSet<>(languageDiagnosticContributions.keySet())) {
                        var contribution = languageDiagnosticContributions.get(owner).get(uri); if (contribution == null) continue;
                        if (expected == null) expected = contribution.snapshot();
                        if (!expected.equals(contribution.snapshot())) { if (opened == null) ambiguous = true; continue; }
                        combined.addAll(contribution.diagnostics());
                    }
                    if (ambiguous) combined.clear();
                    Long version = expected == null || ambiguous ? null : expected.version();
                    client.publishDiagnostics(new PublishDiagnosticsParams(uri, combined, version == null || version > Integer.MAX_VALUE ? null : version.intValue()));
                }
            }
            """.lines().forEach(w::line);
        w.blankLine();
    }
}
