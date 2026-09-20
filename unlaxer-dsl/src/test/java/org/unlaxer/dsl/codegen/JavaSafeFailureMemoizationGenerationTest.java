package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/** Generation-time safety proof and immutable mapper option surface for issue #194. */
public class JavaSafeFailureMemoizationGenerationTest {

    @Test
    public void stateAndCustomDependenciesTaintEveryAncestor() {
        var grammar = UBNFMapper.parse("""
            grammar MemoSafety {
              @package: example.memo
              token CUSTOM = IdentifierParser
              token MATCHED = org.unlaxer.parser.referencer.MatchedTokenParser
              token USER_STATE = example.StatefulParser
              @root PureRoot ::= Pure ;
              Pure ::= 'x' ;
              CapturePure ::= 'c' @value ;
              @scopeTree(mode=lexical) Stateful ::= 's' ;
              StatefulAncestor ::= Stateful ;
              @declares(symbol=name) Declaration ::= 'd' @name ;
              DeclarationAncestor ::= Declaration ;
              @backref(name=name) Reference ::= 'r' @name ;
              ReferenceAncestor ::= Reference ;
              CustomLeaf ::= CUSTOM ;
              CustomAncestor ::= CustomLeaf ;
              MatchedLeaf ::= MATCHED ;
              MatchedAncestor ::= MatchedLeaf ;
              UserStateLeaf ::= USER_STATE ;
              UserStateAncestor ::= UserStateLeaf ;
            }
            """).grammars().get(0);

        String source = new ParserGenerator().generate(grammar).source();
        assertTrue(declaration(source, "PureRootParser").contains("SafeFailureMemoizable"));
        assertTrue(declaration(source, "PureParser").contains("SafeFailureMemoizable"));
        assertTrue(declaration(source, "CapturePureParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "StatefulParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "StatefulAncestorParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "DeclarationParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "DeclarationAncestorParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "ReferenceParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "ReferenceAncestorParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "CustomLeafParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "CustomAncestorParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "MatchedLeafParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "MatchedAncestorParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "UserStateLeafParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "UserStateAncestorParser").contains("SafeFailureMemoizable"));
    }

    @Test
    public void explicitlySafeSimpleTokenAliasesAreSafeAndPropagateToAncestors() {
        var grammar = UBNFMapper.parse("""
            grammar MemoSafety {
              @package: example.memo
              @memoSafeToken: SAFE_A
              @memoSafeToken: SAFE_B
              token SAFE_A = example.SafeAParser
              token SAFE_B = example.SafeBParser
              token UNKNOWN = example.UnknownParser
              @root Root ::= SafeAncestor | OtherLeaf | UnknownLeaf ;
              SafeAncestor ::= SafeLeaf ;
              SafeLeaf ::= SAFE_A SAFE_B ;
              OtherLeaf ::= 'x' ;
              UnknownLeaf ::= UNKNOWN ;
            }
            """).grammars().get(0);

        String source = new ParserGenerator().generate(grammar).source();
        assertFalse(declaration(source, "RootParser").contains("SafeFailureMemoizable"));
        assertTrue(declaration(source, "SafeAncestorParser").contains("SafeFailureMemoizable"));
        assertTrue(declaration(source, "SafeLeafParser").contains("SafeFailureMemoizable"));
        assertTrue(declaration(source, "OtherLeafParser").contains("SafeFailureMemoizable"));
        assertFalse(declaration(source, "UnknownLeafParser").contains("SafeFailureMemoizable"));
    }

    @Test
    public void mapperOffersOptionsAndLegacyEntryPointsDefaultOff() {
        var grammar = UBNFMapper.parse("""
            grammar MemoMapper {
              @package: example.memo
              @root @mapping(Root, params=[value]) Root ::= 'x' @value ;
            }
            """).grammars().get(0);

        String source = new MapperGenerator().generate(grammar).source();
        assertTrue(source.contains("parseWithOptions(String source, ParseOptions options)"));
        assertTrue(source.contains("parse(source, (String) null, ParseOptions.DEFAULT)"));
        assertTrue(source.contains("ParseContext.withOptions(createRootSourceCompat(source), options)"));
        assertTrue(source.contains("diagnose(String source, ParseOptions options)"));
    }

    private static String declaration(String source, String className) {
        int start = source.indexOf("class " + className);
        int end = source.indexOf('{', start);
        return source.substring(start, end);
    }
}
