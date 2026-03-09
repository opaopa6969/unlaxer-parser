package org.unlaxer.dsl;

/**
 * Raised when CLI JSON report payload does not match a schema contract.
 */
final class ReportSchemaValidationException extends RuntimeException {

    private final String code;

    ReportSchemaValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
