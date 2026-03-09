package org.unlaxer.dsl;

import java.util.List;

final class CliFixtureData {

    private CliFixtureData() {}

    static final String VALID_GRAMMAR = """
        grammar Valid {
          @package: org.example.valid
          @root
          @mapping(RootNode, params=[value])
          Valid ::= 'ok' @value ;
        }
        """;

    static final String WARN_ONLY_GRAMMAR = """
        grammar WarnOnly {
          @package: org.example.warn
          @mapping(RootNode, params=[value])
          Start ::= 'ok' @value ;
        }
        """;

    static final List<String> GOLDEN_FILES = List.of(
        "cli/validate_fail_warning.json",
        "cli/validate_fail_warnings_count.json",
        "cli/generate_fail_conflict.json",
        "cli/generate_fail_skipped.json",
        "cli/generate_fail_cleaned.json",
        "cli/generate_fail_conflict.manifest.ndjson"
    );
}
