package org.unlaxer.dsl;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

public class ReportJsonWriterTest {

    @Test
    public void testUnsupportedReportVersionFails() {
        try {
            ReportJsonWriter.validationSuccess(999, "dev", "hash", "2026-01-01T00:00:00Z", 1, 0);
            fail("expected unsupported reportVersion error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Unsupported reportVersion"));
        }
    }

    @Test
    public void testUnsupportedReportVersionFailsForFailurePayload() {
        var row = new ReportJsonWriter.ValidationIssueRow(
            "G", "Start", "E-X", "ERROR", "GENERAL", "m", "h"
        );
        try {
            ReportJsonWriter.validationFailure(999, "dev", "hash", "2026-01-01T00:00:00Z", null, List.of(row));
            fail("expected unsupported reportVersion error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Unsupported reportVersion"));
        }
    }
}
