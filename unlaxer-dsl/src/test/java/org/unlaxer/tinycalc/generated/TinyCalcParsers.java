package org.unlaxer.tinycalc.generated;

import java.util.Optional;
import java.util.function.Supplier;
import org.unlaxer.RecursiveMode;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.clang.IdentifierParser;
import org.unlaxer.parser.elementary.NumberParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.SpaceParser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;
import org.unlaxer.util.cache.SupplierBoundCache;

public class TinyCalcParsers {

    // --- Whitespace Delimitor ---
    public static class TinyCalcSpaceDelimitor extends LazyZeroOrMore {
        private static final long serialVersionUID = 1L;
        @Override
        public Supplier<Parser> getLazyParser() {
            return new SupplierBoundCache<>(() -> Parser.get(SpaceParser.class));
        }
        @Override
        public java.util.Optional<Parser> getLazyTerminatorParser() { return java.util.Optional.empty(); }
    }

    // --- Base Chain ---
    public static abstract class TinyCalcLazyChain extends LazyChain {
        private static final long serialVersionUID = 1L;
        private static final TinyCalcSpaceDelimitor SPACE = createSpace();
        private static TinyCalcSpaceDelimitor createSpace() {
            TinyCalcSpaceDelimitor s = new TinyCalcSpaceDelimitor();
            s.addTag(NodeKind.notNode.getTag());
            return s;
        }
        @Override
        public void prepareChildren(Parsers c) {
            if (!c.isEmpty()) return;
            c.add(SPACE);
            for (Parser p : getLazyParsers()) { c.add(p); c.add(SPACE); }
        }
        public abstract Parsers getLazyParsers();
        @Override
        public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }
    }

    // --- TinyCalc (root rule) ---
    public static class TinyCalcParser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new ZeroOrMore(VariableDeclarationParser.class),
                Parser.get(ExpressionParser.class)
            );
        }
    }

    // --- VariableDeclaration ---
    public static class VariableDeclarationGroup0Parser extends LazyChoice {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            // 長いキーワードを先に置く ("var" が "variable" のプレフィックスに誤マッチするのを防ぐ)
            return new Parsers(
                new WordParser("variable"),
                new WordParser("var")
            );
        }
        @Override
        public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }
    }

    public static class VariableDeclarationOpt0Parser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("set"),
                Parser.get(ExpressionParser.class)
            );
        }
    }

    public static class VariableDeclarationParser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(VariableDeclarationGroup0Parser.class),
                Parser.get(IdentifierParser.class),
                new org.unlaxer.parser.combinator.Optional(VariableDeclarationOpt0Parser.class),
                new WordParser(";")
            );
        }
    }

    // --- Expression ---
    public static class ExpressionGroup0Parser extends LazyChoice {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("+"),
                new WordParser("-")
            );
        }
        @Override
        public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }
    }

    // Helper: { ( '+' | '-' ) Term }  の body 部分
    public static class ExpressionRepeat0Parser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(ExpressionGroup0Parser.class),
                Parser.get(TermParser.class)
            );
        }
    }

    public static class ExpressionParser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(TermParser.class),
                new ZeroOrMore(ExpressionRepeat0Parser.class)
            );
        }
    }

    // --- Term ---
    public static class TermGroup0Parser extends LazyChoice {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("*"),
                new WordParser("/")
            );
        }
        @Override
        public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }
    }

    // Helper: { ( '*' | '/' ) Factor } の body 部分
    public static class TermRepeat0Parser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(TermGroup0Parser.class),
                Parser.get(FactorParser.class)
            );
        }
    }

    public static class TermParser extends TinyCalcLazyChain {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(FactorParser.class),
                new ZeroOrMore(TermRepeat0Parser.class)
            );
        }
    }

    // --- Factor ---
    public static class FactorParser extends LazyChoice {
        private static final long serialVersionUID = 1L;
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new TinyCalcLazyChain() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public Parsers getLazyParsers() {
                        return new Parsers(
                            new WordParser("("),
                            Parser.get(ExpressionParser.class),
                            new WordParser(")")
                        );
                    }
                },
                Parser.get(NumberParser.class),
                Parser.get(IdentifierParser.class)
            );
        }
        @Override
        public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }
    }

    public static Parser getRootParser() {
        return Parser.get(TinyCalcParser.class);
    }
}
