package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.EvalAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GrammarDecl から XxxEvaluator.java を生成する。
 *
 * <p>Java 21 sealed switch で dispatch する abstract class を生成する。
 * DebugStrategy インターフェースと StepCounterStrategy / NOOP 実装を内包する。</p>
 *
 * <p>{@code @eval} アノテーションが付与されたルールに対しては、{@code strategy} と
 * {@code kind} に応じて具象メソッドを生成する。アノテーションが無いか
 * {@code strategy='manual'} の場合は従来どおり abstract メソッドを生成する。</p>
 */
public class EvaluatorGenerator implements CodeGenerator {

    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String packageName = getPackageName(grammar);
        String grammarName = grammar.name();
        String astClass = grammarName + "AST";
        String evalClass = grammarName + "Evaluator";

        // @mapping ルールからユニークなクラス名→RuleDecl を収集（順序保持）
        Map<String, RuleDecl> mappedRules = new LinkedHashMap<>();
        for (RuleDecl rule : grammar.rules()) {
            getMappingAnnotation(rule).ifPresent(m -> mappedRules.putIfAbsent(m.className(), rule));
        }

        // 具象メソッドが1つでもあるかチェック（追加 abstract ヘルパーの生成判定用）
        boolean needsEvalLeaf = false;
        boolean needsApplyBinary = false;
        boolean needsResolveVariable = false;

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
        if (mappedRules.isEmpty()) {
            sb.append("    private T evalInternal(").append(astClass).append(" node) {\n");
            sb.append("        throw new UnsupportedOperationException(\"No mapping classes for evaluation\");\n");
            sb.append("    }\n\n");
        } else {
            // evalInternal（sealed switch dispatch）
            sb.append("    private T evalInternal(").append(astClass).append(" node) {\n");
            sb.append("        return switch (node) {\n");
            for (String name : mappedRules.keySet()) {
                String methodName = "eval" + name;
                sb.append("            case ").append(astClass).append(".").append(name)
                  .append(" n -> ").append(methodName).append("(n);\n");
            }
            sb.append("        };\n");
            sb.append("    }\n\n");

            // メソッド群（abstract or concrete）
            StringBuilder methodsSb = new StringBuilder();
            for (Map.Entry<String, RuleDecl> entry : mappedRules.entrySet()) {
                String name = entry.getKey();
                RuleDecl rule = entry.getValue();
                String methodName = "eval" + name;
                Optional<EvalAnnotation> evalOpt = getEvalAnnotation(rule);

                if (evalOpt.isPresent() && !"manual".equals(evalOpt.get().strategy())) {
                    EvalAnnotation eval = evalOpt.get();
                    String kind = eval.kind();
                    switch (kind) {
                        case "binary_arithmetic" -> {
                            generateBinaryArithmeticMethod(methodsSb, astClass, name, methodName, eval);
                            needsEvalLeaf = true;
                            needsApplyBinary = true;
                        }
                        case "variable_ref" -> {
                            generateVariableRefMethod(methodsSb, astClass, name, methodName, eval);
                            needsResolveVariable = true;
                        }
                        case "conditional" -> {
                            generateConditionalMethod(methodsSb, astClass, name, methodName, eval);
                        }
                        case "passthrough" -> {
                            generatePassthroughMethod(methodsSb, astClass, name, methodName, eval, grammarName);
                        }
                        case "literal" -> {
                            generateLiteralMethod(methodsSb, astClass, name, methodName, eval);
                        }
                        default -> {
                            // Unknown kind with non-manual strategy — generate abstract
                            methodsSb.append("    protected abstract T ").append(methodName).append("(")
                              .append(astClass).append(".").append(name).append(" node);\n");
                        }
                    }
                } else {
                    // No @eval or strategy='manual' → abstract
                    methodsSb.append("    protected abstract T ").append(methodName).append("(")
                      .append(astClass).append(".").append(name).append(" node);\n");
                }
            }
            sb.append(methodsSb);
            sb.append("\n");

            // 追加の abstract ヘルパーメソッド
            if (needsEvalLeaf) {
                sb.append("    protected abstract T evalLeaf(String literal);\n");
            }
            if (needsApplyBinary) {
                sb.append("    protected abstract T applyBinary(String op, T left, T right);\n");
            }
            if (needsResolveVariable) {
                sb.append("    protected abstract T resolveVariable(String name);\n");
            }
            if (needsEvalLeaf || needsApplyBinary || needsResolveVariable) {
                sb.append("\n");
            }
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

    // =========================================================================
    // @eval concrete method generators
    // =========================================================================

    private void generateBinaryArithmeticMethod(StringBuilder sb, String astClass, String name,
            String methodName, EvalAnnotation eval) {
        sb.append("    protected T ").append(methodName).append("(")
          .append(astClass).append(".").append(name).append(" node) {\n");
        sb.append("        if (node.left() == null && node.right().isEmpty() && node.op().size() == 1) {\n");
        sb.append("            return evalLeaf(node.op().get(0));\n");
        sb.append("        }\n");
        sb.append("        if (node.left() != null && node.op().isEmpty() && node.right().isEmpty()) {\n");
        sb.append("            return eval(node.left());\n");
        sb.append("        }\n");
        sb.append("        T current = eval(node.left());\n");
        sb.append("        for (int i = 0; i < Math.min(node.op().size(), node.right().size()); i++) {\n");
        sb.append("            current = applyBinary(node.op().get(i), current, eval(node.right().get(i)));\n");
        sb.append("        }\n");
        sb.append("        return current;\n");
        sb.append("    }\n");
    }

    private void generateVariableRefMethod(StringBuilder sb, String astClass, String name,
            String methodName, EvalAnnotation eval) {
        String stripPrefix = eval.params().getOrDefault("strip_prefix", "");
        sb.append("    protected T ").append(methodName).append("(")
          .append(astClass).append(".").append(name).append(" node) {\n");
        sb.append("        String name = node.name();\n");
        if (!stripPrefix.isEmpty()) {
            sb.append("        if (name != null && name.startsWith(\"")
              .append(escapeJavaString(stripPrefix)).append("\")) name = name.substring(")
              .append(stripPrefix.length()).append(");\n");
        }
        sb.append("        return resolveVariable(name);\n");
        sb.append("    }\n");
    }

    private void generateConditionalMethod(StringBuilder sb, String astClass, String name,
            String methodName, EvalAnnotation eval) {
        sb.append("    protected T ").append(methodName).append("(")
          .append(astClass).append(".").append(name).append(" node) {\n");
        sb.append("        Object cond = eval(node.condition());\n");
        sb.append("        boolean condBool = Boolean.TRUE.equals(cond) || \"true\".equalsIgnoreCase(String.valueOf(cond));\n");
        sb.append("        return condBool ? eval(node.thenExpr()) : eval(node.elseExpr());\n");
        sb.append("    }\n");
    }

    private void generatePassthroughMethod(StringBuilder sb, String astClass, String name,
            String methodName, EvalAnnotation eval, String grammarName) {
        String p4AstClass = grammarName + "AST";
        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    protected T ").append(methodName).append("(")
          .append(astClass).append(".").append(name).append(" node) {\n");
        sb.append("        if (node.value() instanceof ").append(p4AstClass).append(" ast) return eval(ast);\n");
        sb.append("        return (T) node.value();\n");
        sb.append("    }\n");
    }

    private void generateLiteralMethod(StringBuilder sb, String astClass, String name,
            String methodName, EvalAnnotation eval) {
        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    protected T ").append(methodName).append("(")
          .append(astClass).append(".").append(name).append(" node) {\n");
        sb.append("        return (T) node.value();\n");
        sb.append("    }\n");
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private Optional<EvalAnnotation> getEvalAnnotation(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof EvalAnnotation)
            .map(a -> (EvalAnnotation) a)
            .findFirst();
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

    private static String escapeJavaString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
