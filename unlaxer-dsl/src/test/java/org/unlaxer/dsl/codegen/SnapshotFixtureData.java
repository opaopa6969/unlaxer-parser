package org.unlaxer.dsl.codegen;

import java.util.List;

final class SnapshotFixtureData {

    private SnapshotFixtureData() {}

    static final String SNAPSHOT_GRAMMAR = """
        grammar Snapshot {
          @package: org.example.snapshot
          @whitespace: javaStyle

          token NUMBER = NumberParser

          @root
          @mapping(ExprNode, params=[left, op, right])
          @leftAssoc
          @precedence(level=10)
          Expr ::= Term @left { '+' @op Term @right } ;

          @mapping(TermNode, params=[left, op, right])
          @leftAssoc
          @precedence(level=20)
          Term ::= Factor @left { '*' @op Factor @right } ;

          Factor ::= NUMBER ;
        }
        """;

    static final String RIGHT_ASSOC_SNAPSHOT_GRAMMAR = """
        grammar SnapshotRightAssoc {
          @package: org.example.snapshot
          @whitespace: javaStyle

          token NUMBER = NumberParser

          @root
          @mapping(PowNode, params=[left, op, right])
          @rightAssoc
          @precedence(level=30)
          Expr ::= Atom @left { '^' @op Expr @right } ;

          Atom ::= NUMBER ;
        }
        """;

    static final List<String> GOLDEN_FILES = List.of(
        "ast_snapshot.java.txt",
        "evaluator_snapshot.java.txt",
        "parser_snapshot.java.txt",
        "mapper_snapshot.java.txt",
        "lsp_snapshot.java.txt",
        "lsp_launcher_snapshot.java.txt",
        "dap_snapshot.java.txt",
        "dap_launcher_snapshot.java.txt",
        "parser_right_assoc_snapshot.java.txt",
        "mapper_right_assoc_snapshot.java.txt"
    );
}
