package org.unlaxer.dsl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes deterministic args hash from semantic CLI options.
 */
final class ArgsHashUtil {

    private ArgsHashUtil() {}

    static String fromOptions(CodegenCliParser.CliOptions config) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            updateHash(md, "version", "1");
            updateHash(md, "grammar", config.grammarFile());
            updateHash(md, "output", config.outputDir());
            updateHash(md, "generators", String.join(",", config.generators()));
            updateHash(md, "validateOnly", Boolean.toString(config.validateOnly()));
            updateHash(md, "dryRun", Boolean.toString(config.dryRun()));
            updateHash(md, "cleanOutput", Boolean.toString(config.cleanOutput()));
            updateHash(md, "strict", Boolean.toString(config.strict()));
            updateHash(md, "reportFormat", config.reportFormat());
            updateHash(md, "validateParserIrFile", config.validateParserIrFile());
            updateHash(md, "exportParserIrFile", config.exportParserIrFile());
            updateHash(md, "manifestFormat", config.manifestFormat());
            updateHash(md, "reportVersion", Integer.toString(config.reportVersion()));
            updateHash(md, "reportSchemaCheck", Boolean.toString(config.reportSchemaCheck()));
            updateHash(md, "warningsAsJson", Boolean.toString(config.warningsAsJson()));
            updateHash(md, "overwrite", config.overwrite());
            updateHash(md, "failOn", config.failOn());
            updateHash(md, "failOnWarningsThreshold", Integer.toString(config.failOnWarningsThreshold()));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void updateHash(MessageDigest md, String key, String value) {
        md.update(key.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '=');
        if (value != null) {
            md.update(value.getBytes(StandardCharsets.UTF_8));
        }
        md.update((byte) 0);
    }
}
