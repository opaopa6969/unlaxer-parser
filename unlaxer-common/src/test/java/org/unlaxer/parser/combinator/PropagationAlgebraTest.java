package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Independent finite enumeration of the paper's two-axis abstraction, not parser equivalence. */
public class PropagationAlgebraTest {
    // States: (M,F), (M,T), (C,F), (C,T). A map lists four output state indices.
    private static final List<Integer> ID = List.of(0, 1, 2, 3);
    private static final List<Integer> CONSUME = List.of(2, 3, 2, 3);
    private static final List<Integer> STOP = List.of(0, 0, 2, 2);
    private static final List<Integer> NOT = List.of(1, 0, 3, 2);
    private static final List<Integer> ALL = List.of(2, 2, 2, 2);

    private static List<Integer> compose(List<Integer> f, List<Integer> g) {
        return g.stream().map(f::get).toList();
    }

    @Test
    public void closureContainsEightMapsAndTwoLeftZeros() {
        Set<List<Integer>> closure = new LinkedHashSet<>(List.of(ID, ALL, CONSUME, STOP, NOT));
        boolean changed;
        do {
            changed = false;
            List<List<Integer>> snapshot = new ArrayList<>(closure);
            for (var f : snapshot) for (var g : snapshot) changed |= closure.add(compose(f, g));
        } while (changed);
        assertEquals(8, closure.size());
        var force = compose(NOT, STOP);
        var allTrue = compose(CONSUME, force);
        assertEquals(List.of(3, 3, 3, 3), allTrue);
        assertNotEquals(compose(STOP, NOT), compose(NOT, STOP));
        for (var f : closure) {
            assertEquals(f, compose(f, ID));
            assertEquals(f, compose(ID, f));
            assertEquals(ALL, compose(ALL, f));
            assertEquals(allTrue, compose(allTrue, f));
            for (var g : closure) {
                assertTrue(closure.contains(compose(f, g)));
                for (var h : closure) assertEquals(compose(compose(f, g), h), compose(f, compose(g, h)));
            }
        }
        assertEquals(allTrue, compose(NOT, ALL)); // AllStop is not a right zero.
        String[] names = {"Id", "AllStop", "DoConsume", "StopInvert", "NotProp", "ConsumeNot", "ForceInvert", "AllTrue"};
        var maps = List.of(ID, ALL, CONSUME, STOP, NOT, compose(CONSUME, NOT), force, allTrue);
        System.out.println("Propagation algebra: rows f, columns g; f after g");
        System.out.println("| f / g | " + String.join(" | ", names) + " |");
        for (int i = 0; i < maps.size(); i++) {
            var row = new ArrayList<String>();
            for (var g : maps) row.add(names[maps.indexOf(compose(maps.get(i), g))]);
            System.out.println("| " + names[i] + " | " + String.join(" | ", row) + " |");
        }
    }
}
