package org.unlaxer.dsl.bootstrap;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Declared UBNF format-2 capability set; unknown features are rejected, never ignored. */
public final class UBNFFeatures {
    public static final Set<String> SUPPORTED = Set.of(
        "tokenContractsV1", "contextAccessorsV1", "tokenProgressContractsV1", "declarativeTokensV1");

    public record Diagnostic(String code, String subject) {}

    private UBNFFeatures() {}

    public static List<Diagnostic> validate(UBNFAST.GrammarDecl grammar) {
        boolean format2 = grammar.settings().stream().anyMatch(setting -> setting.key().equals("ubnf")
            && setting.value() instanceof UBNFAST.StringSettingValue value && value.value().equals("v2"));
        Set<String> seen = new LinkedHashSet<>();
        return grammar.settings().stream().filter(setting -> setting.key().equals("feature"))
            .map(setting -> {
                if (!format2) return new Diagnostic("E-FEATURE-VERSION", "feature");
                if (!(setting.value() instanceof UBNFAST.StringSettingValue value)
                    || !SUPPORTED.contains(value.value())) return new Diagnostic("E-FEATURE-UNKNOWN", "feature");
                if (!seen.add(value.value())) return new Diagnostic("E-FEATURE-DUPLICATE", value.value());
                return null;
            }).filter(java.util.Objects::nonNull).toList();
    }
}
