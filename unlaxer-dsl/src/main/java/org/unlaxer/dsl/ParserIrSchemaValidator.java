package org.unlaxer.dsl;

import java.util.Map;

import org.unlaxer.dsl.ir.ParserIrConformanceValidator;
import org.unlaxer.dsl.ir.ParserIrDocument;

/**
 * Validates parser IR JSON payloads against runtime conformance rules.
 */
final class ParserIrSchemaValidator {
    private ParserIrSchemaValidator() {}

    static void validate(String payload) {
        Map<String, Object> obj = JsonMiniParser.parseObject(payload, "E-PARSER-IR");
        try {
            ParserIrConformanceValidator.validate(new ParserIrDocument(obj));
        } catch (IllegalArgumentException e) {
            throw new ReportSchemaValidationException("E-PARSER-IR-CONSTRAINT", e.getMessage());
        }
    }
}
