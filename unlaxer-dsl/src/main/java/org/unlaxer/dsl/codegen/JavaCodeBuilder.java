package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Type-safe Java source code builder for code generators.
 * <p>
 * Extends the concept of {@code SimpleBuilder} with Java-specific structure:
 * classes, methods, fields, statements, expressions — all with automatic
 * indentation, semicolons, braces, and type safety.
 *
 * <pre>{@code
 * JavaCodeBuilder java = new JavaCodeBuilder("com.example");
 * java.imports("java.util.List", "java.util.Optional");
 * java.publicClass("MyMapper", cls -> {
 *     cls.field("private static final", "Map<String,String>", "CACHE", "new HashMap<>()");
 *     cls.blankLine();
 *     cls.method("public static", "MyAST", "parse", m -> {
 *         m.param("String", "source");
 *         m.body(b -> {
 *             b.varDecl("Parser", "parser", "getParser()");
 *             b.ifBlock("parser == null", ib -> {
 *                 ib.throwNew("IllegalArgumentException", quoted("No parser"));
 *             });
 *             b.returnStmt("parser.parse(source)");
 *         });
 *     });
 * });
 * String javaSource = java.build();
 * }</pre>
 */
public class JavaCodeBuilder {

  private final StringBuilder sb = new StringBuilder();
  private int indent = 0;
  private static final int TAB = 4;

  private final String packageName;

  public JavaCodeBuilder(String packageName) {
    this.packageName = packageName;
  }

  // =========================================================================
  // Top-level structure
  // =========================================================================

  public JavaCodeBuilder packageDecl() {
    line("package " + packageName + ";");
    blankLine();
    return this;
  }

  public JavaCodeBuilder imports(String... classNames) {
    for (String cn : classNames) {
      line("import " + cn + ";");
    }
    blankLine();
    return this;
  }

  public JavaCodeBuilder javadoc(String... lines) {
    line("/**");
    for (String l : lines) {
      line(" * " + l);
    }
    line(" */");
    return this;
  }

  public JavaCodeBuilder publicClass(String name, Consumer<ClassScope> body) {
    line("public class " + name + " {");
    blankLine();
    indent++;
    body.accept(new ClassScope());
    indent--;
    line("}");
    return this;
  }

  public JavaCodeBuilder publicSealedInterface(String name, List<String> permits, Consumer<ClassScope> body) {
    StringBuilder decl = new StringBuilder("public sealed interface " + name);
    if (permits != null && !permits.isEmpty()) {
      decl.append(" permits\n");
      for (int i = 0; i < permits.size(); i++) {
        decl.append(indentStr(indent + 1)).append(permits.get(i));
        decl.append(i < permits.size() - 1 ? ",\n" : " {\n");
      }
      sb.append(indentStr(indent)).append(decl);
    } else {
      line(decl + " {");
    }
    blankLine();
    indent++;
    body.accept(new ClassScope());
    indent--;
    line("}");
    return this;
  }

  // =========================================================================
  // ClassScope — fields, methods, inner classes
  // =========================================================================

  public class ClassScope {

    public ClassScope field(String modifiers, String type, String name) {
      line(modifiers + " " + type + " " + name + ";");
      return this;
    }

    public ClassScope field(String modifiers, String type, String name, String initializer) {
      line(modifiers + " " + type + " " + name + " = " + initializer + ";");
      return this;
    }

    public ClassScope constructor(String modifiers, String className, Consumer<MethodScope> body) {
      MethodScope m = new MethodScope();
      line(modifiers + " " + className + "(" + m.paramsString() + ") {");
      indent++;
      body.accept(m);
      indent--;
      line("}");
      blankLine();
      return this;
    }

    public ClassScope method(String modifiers, String returnType, String name, Consumer<MethodScope> body) {
      MethodScope m = new MethodScope();
      body.accept(m);
      line(modifiers + " " + returnType + " " + name + "(" + m.paramsString() + ") {");
      indent++;
      m.emitBody();
      indent--;
      line("}");
      blankLine();
      return this;
    }

    public ClassScope abstractMethod(String modifiers, String returnType, String name, String... params) {
      StringBuilder paramStr = new StringBuilder();
      for (int i = 0; i < params.length; i += 2) {
        if (i > 0) paramStr.append(", ");
        paramStr.append(params[i]).append(" ").append(params[i + 1]);
      }
      line(modifiers + " " + returnType + " " + name + "(" + paramStr + ");");
      return this;
    }

    public ClassScope record(String name, List<String[]> components) {
      StringBuilder decl = new StringBuilder("record " + name + "(");
      for (int i = 0; i < components.size(); i++) {
        if (i > 0) decl.append(", ");
        decl.append(components.get(i)[0]).append(" ").append(components.get(i)[1]);
      }
      decl.append(") {}");
      line(decl.toString());
      return this;
    }

    public ClassScope blankLine() {
      JavaCodeBuilder.this.blankLine();
      return this;
    }

    public ClassScope comment(String text) {
      line("// " + text);
      return this;
    }

    public ClassScope rawLine(String text) {
      line(text);
      return this;
    }
  }

  // =========================================================================
  // MethodScope — parameters and body
  // =========================================================================

  public class MethodScope {
    private final List<String[]> params = new ArrayList<>();
    private Consumer<BodyScope> bodyConsumer;

    public MethodScope param(String type, String name) {
      params.add(new String[]{type, name});
      return this;
    }

    public MethodScope body(Consumer<BodyScope> body) {
      this.bodyConsumer = body;
      return this;
    }

    String paramsString() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < params.size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(params.get(i)[0]).append(" ").append(params.get(i)[1]);
      }
      return sb.toString();
    }

    void emitBody() {
      if (bodyConsumer != null) {
        bodyConsumer.accept(new BodyScope());
      }
    }
  }

  // =========================================================================
  // BodyScope — statements
  // =========================================================================

  public class BodyScope {

    public BodyScope varDecl(String type, String name, String initializer) {
      line(type + " " + name + " = " + initializer + ";");
      return this;
    }

    public BodyScope assign(String target, String value) {
      line(target + " = " + value + ";");
      return this;
    }

    public BodyScope stmt(String statement) {
      line(statement + ";");
      return this;
    }

    public BodyScope rawLine(String text) {
      line(text);
      return this;
    }

    public BodyScope returnStmt(String expression) {
      line("return " + expression + ";");
      return this;
    }

    public BodyScope throwNew(String exceptionType, String message) {
      line("throw new " + exceptionType + "(" + message + ");");
      return this;
    }

    public BodyScope ifBlock(String condition, Consumer<BodyScope> body) {
      line("if (" + condition + ") {");
      indent++;
      body.accept(new BodyScope());
      indent--;
      line("}");
      return this;
    }

    public BodyScope ifElseBlock(String condition, Consumer<BodyScope> thenBody, Consumer<BodyScope> elseBody) {
      line("if (" + condition + ") {");
      indent++;
      thenBody.accept(new BodyScope());
      indent--;
      line("} else {");
      indent++;
      elseBody.accept(new BodyScope());
      indent--;
      line("}");
      return this;
    }

    public BodyScope forLoop(String init, String condition, String increment, Consumer<BodyScope> body) {
      line("for (" + init + "; " + condition + "; " + increment + ") {");
      indent++;
      body.accept(new BodyScope());
      indent--;
      line("}");
      return this;
    }

    public BodyScope forEachLoop(String type, String varName, String iterable, Consumer<BodyScope> body) {
      line("for (" + type + " " + varName + " : " + iterable + ") {");
      indent++;
      body.accept(new BodyScope());
      indent--;
      line("}");
      return this;
    }

    public BodyScope switchExpr(String subject, Consumer<SwitchScope> cases) {
      line("return switch (" + subject + ") {");
      indent++;
      cases.accept(new SwitchScope());
      indent--;
      line("};");
      return this;
    }

    public BodyScope tryCatch(Consumer<BodyScope> tryBody, String exType, String exName, Consumer<BodyScope> catchBody) {
      line("try {");
      indent++;
      tryBody.accept(new BodyScope());
      indent--;
      line("} catch (" + exType + " " + exName + ") {");
      indent++;
      catchBody.accept(new BodyScope());
      indent--;
      line("}");
      return this;
    }

    public BodyScope blankLine() {
      JavaCodeBuilder.this.blankLine();
      return this;
    }

    public BodyScope comment(String text) {
      line("// " + text);
      return this;
    }
  }

  // =========================================================================
  // SwitchScope
  // =========================================================================

  public class SwitchScope {
    public SwitchScope caseArrow(String pattern, String expression) {
      line("case " + pattern + " -> " + expression + ";");
      return this;
    }

    public SwitchScope caseBlock(String pattern, Consumer<BodyScope> body) {
      line("case " + pattern + " -> {");
      indent++;
      body.accept(new BodyScope());
      indent--;
      line("}");
      return this;
    }

    public SwitchScope defaultArrow(String expression) {
      line("default -> " + expression + ";");
      return this;
    }
  }

  // =========================================================================
  // Expression helpers
  // =========================================================================

  public static String quoted(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  public static String ternary(String cond, String then, String otherwise) {
    return "(" + cond + " ? " + then + " : " + otherwise + ")";
  }

  public static String cast(String type, String expr) {
    return "((" + type + ") " + expr + ")";
  }

  public static String instanceOf(String expr, String type, String varName) {
    return expr + " instanceof " + type + " " + varName;
  }

  public static String methodCall(String target, String method, String... args) {
    return target + "." + method + "(" + String.join(", ", args) + ")";
  }

  // =========================================================================
  // Internal
  // =========================================================================

  private void line(String text) {
    sb.append(indentStr(indent)).append(text).append('\n');
  }

  private void blankLine() {
    sb.append('\n');
  }

  private static String indentStr(int level) {
    return " ".repeat(level * TAB);
  }

  public String build() {
    StringBuilder result = new StringBuilder();
    if (packageName != null && !packageName.isEmpty()) {
      result.append("package ").append(packageName).append(";\n\n");
    }
    result.append(sb);
    return result.toString();
  }

  @Override
  public String toString() {
    return build();
  }
}
