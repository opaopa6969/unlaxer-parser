package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.unlaxer.dsl.codegen.rust.GrammarIR.*;

/** Public schema errors are rejected independently of source parsing and code emission. */
public class TypedNestedSchemaTest {
    @Test public void recursiveSchemaIsImmutableAndRejectsUnsupportedPublicShapes() {
        var containers=new ArrayList<>(List.of(Cardinality.OPTIONAL,Cardinality.MANY));
        var shape=new NestedShape(Kind.INTEGER,null,containers);
        containers.clear();
        assertEquals(List.of(Cardinality.OPTIONAL,Cardinality.MANY),shape.containers());
        assertThrows(UnsupportedOperationException.class,()->shape.containers().clear());
        assertEquals("Optional<List<Integer>>",NestedCaptures.javaType(shape));
        assertThrows(IllegalArgumentException.class,()->new NestedShape(Kind.NODE,null,List.of()));
        assertEquals("Optional<List<GrammarAST.Leaf>>",NestedCaptures.javaType(new NestedShape(Kind.NODE,null,List.of(Cardinality.OPTIONAL,Cardinality.MANY),"GrammarAST.Leaf")));
        assertEquals("Optional<GrammarAST.Mode>",NestedCaptures.javaType(new NestedShape(Kind.ENUM,"Mode",List.of(Cardinality.OPTIONAL),"GrammarAST.Mode")));
        assertThrows(IllegalArgumentException.class,()->new NestedShape(Kind.ENUM,null,List.of(),"GrammarAST.Mode"));
        assertThrows(IllegalArgumentException.class,()->new NestedShape(Kind.TEXT,null,List.of(),"GrammarAST.Leaf"));
        assertThrows(IllegalArgumentException.class,()->new NestedShape(Kind.TEXT,"Mode",List.of()));
        assertThrows(IllegalArgumentException.class,()->new NestedShape(Kind.TEXT,null,List.of(Cardinality.ONE)));
        assertThrows(IllegalArgumentException.class,()->new NestedShape(Kind.TEXT,null,java.util.Collections.nCopies(33,Cardinality.OPTIONAL)));
        assertThrows(IllegalArgumentException.class,()->new Field("value",Kind.NESTED,Cardinality.ONE));
        assertThrows(IllegalArgumentException.class,()->new Field("value",Kind.NESTED,Cardinality.MANY,null,shape));
        assertThrows(IllegalArgumentException.class,()->new Field("value",Kind.TEXT,Cardinality.ONE,null,shape));
        assertEquals(shape,new Field("value",Kind.NESTED,Cardinality.ONE,null,shape).nested());
        assertEquals(Kind.TEXT,new Field("legacy",Kind.TEXT,Cardinality.MANY).kind());
    }
}
