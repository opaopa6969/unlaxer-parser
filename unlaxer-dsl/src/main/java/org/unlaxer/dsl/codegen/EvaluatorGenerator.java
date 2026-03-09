package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;

/**
 * GrammarDecl から XxxEvaluator.java を生成する。
 *
 * <p>Java 21 sealed switch で dispatch する abstract class を生成する。
 * DebugStrategy インターフェースと StepCounterStrategy / NOOP 実装を内包する。</p>
 */
public class EvaluatorGenerator implements CodeGenerator {

    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String packageName = getPackageName(grammar);
        String grammarName = grammar.name();
        String astClass = grammarName + "AST";
        String evalClass = grammarName + "Evaluator";

        // @mapping ルールからユニークなクラス名を収集（順序保持）
        SequencedSet<String> classNames = new LinkedHashSet<>();
        for (RuleDecl rule : grammar.rules()) {
            getMappingAnnotation(rule).ifPresent(m -> classNames.add(m.className()));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("public abstract class ").append(evalClass).append("<T> {\n\n");

        // DebugStrategy フィールドとセッター
        sb.append("    private DebugStrategy debugStrategy = DebugStrategy.NOOP;\n\n");
        sb.append("    public void setDebugStrategy(DebugStrategy strategy) {\n");
        sb.append("        this.debugStrategy = strategy;\n");
        sb.append("    }\n\n");

        // eval メソッド（public エントリーポイント）
        sb.append("    public T eval(").append(astClass).append(" node) {\n");
        sb.append("        debugStrategy.onEnter(node);\n");
        sb.append("        T result = evalInternal(node);\n");
        sb.append("        debugStrategy.onExit(node, result);\n");
        sb.append("        return result;\n");
        sb.append("    }\n\n");

        // evalInternal（sealed switch dispatch）
        if (classNames.isEmpty()) {
            sb.append("    private T evalInternal(").append(astClass).append(" node) {\n");
            sb.append("        throw new UnsupportedOperationException(\"No mapping classes for evaluation\");\n");
            sb.append("    }\n\n");
        } else {
            // evalInternal（sealed switch dispatch）
            sb.append("    private T evalInternal(").append(astClass).append(" node) {\n");
            sb.append("        return switch (node) {\n");
            for (String name : classNames) {
                String methodName = "eval" + name;
                sb.append("            case ").append(astClass).append(".").append(name)
                  .append(" n -> ").append(methodName).append("(n);\n");
            }
            sb.append("        };\n");
            sb.append("    }\n\n");

            // 抽象メソッド群
            for (String name : classNames) {
                String methodName = "eval" + name;
                sb.append("    protected abstract T ").append(methodName).append("(")
                  .append(astClass).append(".").append(name).append(" node);\n");
            }
            sb.append("\n");
        }

        // DebugStrategy inner interface
        sb.append("    // =========================================================================\n");
        sb.append("    // DebugStrategy\n");
        sb.append("    // =========================================================================\n\n");
        sb.append("    public interface DebugStrategy {\n");
        sb.append("        void onEnter(").append(astClass).append(" node);\n");
        sb.append("        void onExit(").append(astClass).append(" node, Object result);\n\n");
        sb.append("        DebugStrategy NOOP = new DebugStrategy() {\n");
        sb.append("            public void onEnter(").append(astClass).append(" node) {}\n");
        sb.append("            public void onExit(").append(astClass).append(" node, Object result) {}\n");
        sb.append("        };\n");
        sb.append("    }\n\n");

        // StepCounterStrategy inner class
        sb.append("    public static class StepCounterStrategy implements DebugStrategy {\n");
        sb.append("        private int step = 0;\n");
        sb.append("        private final java.util.function.BiConsumer<Integer, ").append(astClass)
          .append("> onStep;\n\n");
        sb.append("        public StepCounterStrategy(\n");
        sb.append("            java.util.function.BiConsumer<Integer, ").append(astClass)
          .append("> onStep\n");
        sb.append("        ) {\n");
        sb.append("            this.onStep = onStep;\n");
        sb.append("        }\n\n");
        sb.append("        @Override\n");
        sb.append("        public void onEnter(").append(astClass).append(" node) {\n");
        sb.append("            onStep.accept(step++, node);\n");
        sb.append("        }\n\n");
        sb.append("        @Override\n");
        sb.append("        public void onExit(").append(astClass)
          .append(" node, Object result) {}\n");
        sb.append("    }\n");

        sb.append("}\n");

        return new GeneratedSource(packageName, evalClass, sb.toString());
    }

    private Optional<MappingAnnotation> getMappingAnnotation(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof MappingAnnotation)
            .map(a -> (MappingAnnotation) a)
            .findFirst();
    }

    private String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }
}
