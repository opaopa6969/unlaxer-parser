package org.unlaxer.dsl.codegen;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

/** Baseline Java Optional/Repeat delimiters through the same typed consumers. */
public class AssociativeNullableDelimiterConformanceTest extends AssociativeDelimiterConformanceTest {
    @Override
    protected JsonArray corpus() throws Exception {
        return JsonParser.parseString(Files.readString(
            Path.of("../spec-corpus/associative-nullable-delimiters/cases.json"))).getAsJsonArray();
    }

    @Override
    protected JsonArray invalidCorpus() throws Exception {
        return JsonParser.parseString(Files.readString(
            Path.of("../spec-corpus/associative-nullable-delimiters/invalid-cases.json"))).getAsJsonArray();
    }
}
