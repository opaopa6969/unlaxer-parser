package org.unlaxer.dsl;

import static org.junit.Assert.fail;
import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class ReportJsonSchemaValidatorTest {

    @Test
    public void testAcceptsValidateSuccessV1() {
        String json = ReportJsonWriter.validationSuccess(1, "dev", "hash", "2026-01-01T00:00:00Z", 1, 0);
        ReportJsonSchemaValidator.validate(1, json);
    }

    @Test
    public void testAcceptsValidateFailureV1() {
        var row = new ReportJsonWriter.ValidationIssueRow(
            "G", "Start", "E-X", "ERROR", "GENERAL", "m", "h"
        );
        String json = ReportJsonWriter.validationFailure(1, "dev", "hash", "2026-01-01T00:00:00Z", null, List.of(row));
        ReportJsonSchemaValidator.validate(1, json);
    }

    @Test
    public void testAcceptsGenerateSuccessV1() {
        String json = ReportJsonWriter.generationResult(
            1,
            "dev",
            "hash",
            "2026-01-01T00:00:00Z",
            true,
            null,
            1,
            List.of("org/example/ValidAST.java"),
            0,
            1,
            0,
            0,
            0
        );
        ReportJsonSchemaValidator.validate(1, json);
    }

    @Test
    public void testAcceptsGenerateFailureWithReasonCodeV1() {
        String json = ReportJsonWriter.generationResult(
            1,
            "dev",
            "hash",
            "2026-01-01T00:00:00Z",
            false,
            "FAIL_ON_CONFLICT",
            1,
            List.of(),
            0,
            0,
            0,
            1,
            0
        );
        ReportJsonSchemaValidator.validate(1, json);
    }

    @Test
    public void testRejectsUnsupportedVersion() {
        String json = ReportJsonWriter.validationSuccess(1, "dev", "hash", "2026-01-01T00:00:00Z", 1, 0);
        try {
            ReportJsonSchemaValidator.validate(2, json);
            fail("expected unsupported version error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-REPORT-SCHEMA-UNSUPPORTED-VERSION", expected.code());
        }
    }

    @Test
    public void testRejectsMissingRequiredKey() {
        String broken = "{\"reportVersion\":1,\"toolVersion\":\"dev\",\"generatedAt\":\"x\",\"ok\":true,\"grammarCount\":1,\"issues\":[]}";
        try {
            ReportJsonSchemaValidator.validate(1, broken);
            fail("expected schema validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-REPORT-SCHEMA-MISSING-KEY", expected.code());
        }
    }

    @Test
    public void testRejectsWrongKeyOrderForV1() {
        String broken = "{\"toolVersion\":\"dev\",\"reportVersion\":1,\"generatedAt\":\"x\",\"mode\":\"validate\",\"ok\":true,\"grammarCount\":1,\"issues\":[]}";
        try {
            ReportJsonSchemaValidator.validate(1, broken);
            fail("expected schema validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-REPORT-SCHEMA-KEY-ORDER", expected.code());
        }
    }

    @Test
    public void testRejectsNonObjectPayload() {
        try {
            ReportJsonSchemaValidator.validate(1, "[]");
            fail("expected schema validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-REPORT-SCHEMA-PARSE", expected.code());
        }
    }

    @Test
    public void testRejectsTrailingCharacters() {
        String valid = ReportJsonWriter.validationSuccess(1, "dev", "hash", "2026-01-01T00:00:00Z", 1, 0);
        try {
            ReportJsonSchemaValidator.validate(1, valid + " trailing");
            fail("expected schema validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-REPORT-SCHEMA-PARSE", expected.code());
        }
    }

    @Test
    public void testRejectsUnknownFailReasonCode() {
        String broken = "{\"reportVersion\":1,\"schemaVersion\":\"1.0\",\"schemaUrl\":\"https://unlaxer.dev/schema/report-v1.json\","
            + "\"toolVersion\":\"dev\",\"argsHash\":\"hash\",\"generatedAt\":\"2026-01-01T00:00:00Z\",\"mode\":\"generate\","
            + "\"ok\":false,\"failReasonCode\":\"UNKNOWN\",\"grammarCount\":1,\"generatedCount\":0,\"warningsCount\":0,"
            + "\"writtenCount\":0,\"skippedCount\":0,\"conflictCount\":1,\"dryRunCount\":0,\"generatedFiles\":[]}";
        try {
            ReportJsonSchemaValidator.validate(1, broken);
            fail("expected schema validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-REPORT-SCHEMA-CONSTRAINT", expected.code());
        }
    }

    @Test
    public void testRejectsNonIsoGeneratedAt() {
        String broken = "{\"reportVersion\":1,\"schemaVersion\":\"1.0\",\"schemaUrl\":\"https://unlaxer.dev/schema/report-v1.json\","
            + "\"toolVersion\":\"dev\",\"argsHash\":\"hash\",\"generatedAt\":\"not-a-date\",\"mode\":\"validate\","
            + "\"ok\":true,\"grammarCount\":1,\"warningsCount\":0,\"issues\":[]}";
        try {
            ReportJsonSchemaValidator.validate(1, broken);
            fail("expected schema validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-REPORT-SCHEMA-CONSTRAINT", expected.code());
        }
    }

    @Test
    public void testRejectsFloatingPointCount() {
        String broken = "{\"reportVersion\":1,\"schemaVersion\":\"1.0\",\"schemaUrl\":\"https://unlaxer.dev/schema/report-v1.json\","
            + "\"toolVersion\":\"dev\",\"argsHash\":\"hash\",\"generatedAt\":\"2026-01-01T00:00:00Z\",\"mode\":\"generate\","
            + "\"ok\":true,\"failReasonCode\":null,\"grammarCount\":1,\"generatedCount\":1,\"warningsCount\":0.5,"
            + "\"writtenCount\":1,\"skippedCount\":0,\"conflictCount\":0,\"dryRunCount\":0,\"generatedFiles\":[\"x\"]}";
        try {
            ReportJsonSchemaValidator.validate(1, broken);
            fail("expected schema validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-REPORT-SCHEMA-TYPE", expected.code());
        }
    }

    @Test
    public void testRejectsGenerateOkTrueWithFailReasonCode() {
        String broken = "{\"reportVersion\":1,\"schemaVersion\":\"1.0\",\"schemaUrl\":\"https://unlaxer.dev/schema/report-v1.json\","
            + "\"toolVersion\":\"dev\",\"argsHash\":\"hash\",\"generatedAt\":\"2026-01-01T00:00:00Z\",\"mode\":\"generate\","
            + "\"ok\":true,\"failReasonCode\":\"FAIL_ON_CONFLICT\",\"grammarCount\":1,\"generatedCount\":1,\"warningsCount\":0,"
            + "\"writtenCount\":1,\"skippedCount\":0,\"conflictCount\":0,\"dryRunCount\":0,\"generatedFiles\":[\"x\"]}";
        try {
            ReportJsonSchemaValidator.validate(1, broken);
            fail("expected schema validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-REPORT-SCHEMA-CONSTRAINT", expected.code());
        }
    }

    @Test
    public void testRejectsGenerateOkFalseWithoutFailReasonCode() {
        String broken = "{\"reportVersion\":1,\"schemaVersion\":\"1.0\",\"schemaUrl\":\"https://unlaxer.dev/schema/report-v1.json\","
            + "\"toolVersion\":\"dev\",\"argsHash\":\"hash\",\"generatedAt\":\"2026-01-01T00:00:00Z\",\"mode\":\"generate\","
            + "\"ok\":false,\"failReasonCode\":null,\"grammarCount\":1,\"generatedCount\":0,\"warningsCount\":0,"
            + "\"writtenCount\":0,\"skippedCount\":0,\"conflictCount\":1,\"dryRunCount\":0,\"generatedFiles\":[]}";
        try {
            ReportJsonSchemaValidator.validate(1, broken);
            fail("expected schema validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-REPORT-SCHEMA-CONSTRAINT", expected.code());
        }
    }
}
