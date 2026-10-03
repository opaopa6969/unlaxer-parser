package org.unlaxer.dsl.tooling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/** Shared, bundled authoring documentation; syntax vocabulary comes from the UBNF meta-grammar AST. */
public final class AuthoringCatalog {
    private AuthoringCatalog() {}

    public static String json() {
        return resource("catalog.json");
    }

    /** Quoted identifiers in the meta grammar, plus declarative zero-width atoms represented by DottedIdentifier. */
    public static List<String> keywords() {
        Set<String> words = new LinkedHashSet<>();
        for (var grammar : UBNFMapper.parse(resource("ubnf.ubnf")).grammars()) {
            for (var rule : grammar.rules()) collect(rule.body(), words);
        }
        words.addAll(List.of("BOF", "BOL", "EOL"));
        return List.copyOf(words);
    }

    private static String resource(String name) {
        try (var input = AuthoringCatalog.class.getResourceAsStream("/ubnf-help/" + name)) {
            if (input == null) throw new IllegalStateException("Missing bundled UBNF help: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled UBNF help: " + name, e);
        }
    }

    private static void collect(UBNFAST.RuleBody body, Set<String> words) {
        if (body instanceof UBNFAST.ChoiceBody choice) {
            for (var alternative : choice.alternatives()) collect(alternative, words);
        } else if (body instanceof UBNFAST.SequenceBody sequence) {
            for (var item : sequence.elements()) collect(item.element(), words);
        }
    }

    private static void collect(UBNFAST.AtomicElement element, Set<String> words) {
        if (element instanceof UBNFAST.TerminalElement terminal) {
            if (terminal.value().matches("[A-Za-z_][A-Za-z_0-9]*")) words.add(terminal.value());
        } else if (element instanceof UBNFAST.GroupElement group) collect(group.body(), words);
        else if (element instanceof UBNFAST.OptionalElement optional) collect(optional.body(), words);
        else if (element instanceof UBNFAST.RepeatElement repeat) collect(repeat.body(), words);
        else if (element instanceof UBNFAST.OneOrMoreElement repeat) collect(repeat.body(), words);
        else if (element instanceof UBNFAST.BoundedRepeatElement repeat) collect(repeat.body(), words);
        else if (element instanceof UBNFAST.SeparatedElement separated) {
            collect(separated.element(), words);
            collect(separated.separator(), words);
        }
    }
}
