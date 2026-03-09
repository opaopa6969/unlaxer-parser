package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class ParserIrSchemaValidatorTest {

    @Test
    public void testAcceptsValidMinimalFixture() throws Exception {
        String payload = loadFixture("valid-minimal.json");
        ParserIrSchemaValidator.validate(payload);
    }

    @Test
    public void testRejectsBlankSourceFixture() throws Exception {
        String payload = loadFixture("invalid-source-blank.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("source must not be blank"));
        }
    }

    @Test
    public void testRejectsBrokenJson() {
        try {
            ParserIrSchemaValidator.validate("{");
            fail("expected parser ir parse error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-PARSE", expected.code());
        }
    }

    @Test
    public void testRejectsNegativeNodeSpanFixture() throws Exception {
        String payload = loadFixture("invalid-node-negative-span.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("non-negative"));
        }
    }

    @Test
    public void testRejectsDuplicateNodeIdFixture() throws Exception {
        String payload = loadFixture("invalid-duplicate-node-id.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("duplicate node id"));
        }
    }

    @Test
    public void testRejectsUnknownAnnotationTargetFixture() throws Exception {
        String payload = loadFixture("invalid-annotation-target-id.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("unknown annotation targetId"));
        }
    }

    @Test
    public void testRejectsDefineMissingKindFixture() throws Exception {
        String payload = loadFixture("invalid-define-missing-kind.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("define requires kind"));
        }
    }

    @Test
    public void testRejectsScopeOrderFixture() throws Exception {
        String payload = loadFixture("invalid-scope-order.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("scope order violated"));
        }
    }

    @Test
    public void testRejectsScopeNestingFixture() throws Exception {
        String payload = loadFixture("invalid-scope-nesting.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("scope nesting violated"));
        }
    }

    @Test
    public void testRejectsScopeSpanOrderFixture() throws Exception {
        String payload = loadFixture("invalid-scope-span-order.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("scopeEvent span.start <= span.end required"));
        }
    }

    @Test
    public void testRejectsUnsupportedScopeModeFixture() throws Exception {
        String payload = loadFixture("invalid-scope-mode.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("unsupported scopeMode"));
        }
    }

    @Test
    public void testRejectsUseWithScopeModeFixture() throws Exception {
        String payload = loadFixture("invalid-use-with-scope-mode.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("use must not include scopeMode"));
        }
    }

    @Test
    public void testRejectsScopeModeMismatchFixture() throws Exception {
        String payload = loadFixture("invalid-scope-mode-mismatch.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("scopeMode mismatch"));
        }
    }

    @Test
    public void testRejectsUnknownTargetScopeIdFixture() throws Exception {
        String payload = loadFixture("invalid-target-scope-id.json");
        try {
            ParserIrSchemaValidator.validate(payload);
            fail("expected parser ir validation error");
        } catch (ReportSchemaValidationException expected) {
            assertEquals("E-PARSER-IR-CONSTRAINT", expected.code());
            assertTrue(expected.getMessage().contains("unknown targetScopeId"));
        }
    }

    private static String loadFixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/schema/parser-ir").resolve(name));
    }
}
