package org.unlaxer.dsl.codegen;

/**
 * Before/After comparison: MapperGenerator using JavaCodeBuilder.
 * <p>
 * This is NOT used in production — it demonstrates the difference
 * in readability between raw StringBuilder and JavaCodeBuilder.
 */
final class JavaCodeBuilderExample {

  private JavaCodeBuilderExample() {}

  // =========================================================================
  // BEFORE: raw StringBuilder (current MapperGenerator style)
  // =========================================================================

  static String beforeStyle() {
    StringBuilder sb = new StringBuilder();
    sb.append("package com.example;\n\n");
    sb.append("import java.util.List;\n");
    sb.append("import java.util.Optional;\n\n");
    sb.append("public class MyMapper {\n\n");
    sb.append("    private MyMapper() {}\n\n");
    sb.append("    public static MyAST parse(String source) {\n");
    sb.append("        return parse(source, null);\n");
    sb.append("    }\n\n");
    sb.append("    public static MyAST parse(String source, String preferred) {\n");
    sb.append("        Parser rootParser = MyParsers.getRootParser();\n");
    sb.append("        ParseContext context = new ParseContext(source);\n");
    sb.append("        Parsed parsed;\n");
    sb.append("        try {\n");
    sb.append("            parsed = rootParser.parse(context);\n");
    sb.append("        } finally {\n");
    sb.append("            context.close();\n");
    sb.append("        }\n");
    sb.append("        if (!parsed.isSucceeded()) {\n");
    sb.append("            throw new IllegalArgumentException(\"Parse failed: \" + source);\n");
    sb.append("        }\n");
    sb.append("        Token rootToken = parsed.getRootToken(true);\n");
    sb.append("        return mapToken(rootToken);\n");
    sb.append("    }\n\n");
    sb.append("    static MyAST.BinaryExpr toBinaryExpr(Token token) {\n");
    sb.append("        Token working = token;\n");
    sb.append("        if (working.parser.getClass() != MyParsers.NumberExpressionParser.class) {\n");
    sb.append("            working = findFirstDescendant(working, MyParsers.NumberExpressionParser.class);\n");
    sb.append("        }\n");
    sb.append("        if (working == null) {\n");
    sb.append("            String literal = stripQuotes(firstTokenText(token));\n");
    sb.append("            return new MyAST.BinaryExpr(null, List.of(literal), List.of());\n");
    sb.append("        }\n");
    sb.append("        // ... 50 more lines of sb.append ...\n");
    sb.append("        return null;\n");
    sb.append("    }\n");
    sb.append("}\n");
    return sb.toString();
  }

  // =========================================================================
  // AFTER: JavaCodeBuilder (proposed style)
  // =========================================================================

  static String afterStyle() {
    JavaCodeBuilder java = new JavaCodeBuilder("com.example");
    java.imports("java.util.List", "java.util.Optional");

    java.publicClass("MyMapper", cls -> {

      cls.field("private", "MyMapper()", "/* private constructor */");

      cls.method("public static", "MyAST", "parse", m -> {
        m.param("String", "source");
        m.body(b -> {
          b.returnStmt("parse(source, null)");
        });
      });

      cls.method("public static", "MyAST", "parse", m -> {
        m.param("String", "source");
        m.param("String", "preferred");
        m.body(b -> {
          b.varDecl("Parser", "rootParser", "MyParsers.getRootParser()");
          b.varDecl("ParseContext", "context", "new ParseContext(source)");
          b.rawLine("Parsed parsed;");
          b.tryCatch(
            tryBody -> {
              tryBody.assign("parsed", "rootParser.parse(context)");
            },
            "Throwable", "e",
            catchBody -> {
              catchBody.stmt("context.close()");
              catchBody.rawLine("throw e;");
            }
          );
          b.ifBlock("!parsed.isSucceeded()", ib -> {
            ib.throwNew("IllegalArgumentException", JavaCodeBuilder.quoted("Parse failed: ") + " + source");
          });
          b.varDecl("Token", "rootToken", "parsed.getRootToken(true)");
          b.returnStmt("mapToken(rootToken)");
        });
      });

      cls.method("static", "MyAST.BinaryExpr", "toBinaryExpr", m -> {
        m.param("Token", "token");
        m.body(b -> {
          b.varDecl("Token", "working", "token");
          b.ifBlock("working.parser.getClass() != MyParsers.NumberExpressionParser.class", ib -> {
            ib.assign("working", "findFirstDescendant(working, MyParsers.NumberExpressionParser.class)");
          });
          b.ifBlock("working == null", ib -> {
            ib.varDecl("String", "literal", "stripQuotes(firstTokenText(token))");
            ib.returnStmt("new MyAST.BinaryExpr(null, List.of(literal), List.of())");
          });
          b.comment("... structured code continues ...");
          b.returnStmt("null");
        });
      });
    });

    return java.build();
  }
}
