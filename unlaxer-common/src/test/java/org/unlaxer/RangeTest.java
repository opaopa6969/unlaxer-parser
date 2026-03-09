package org.unlaxer;

import static org.junit.Assert.*;

import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.Test;

public class RangeTest {

	@Test
	public void test() {
		
		Range range1 = new Range(1,3);
		Range range2 = new Range(2);
		Range range3 = new Range(0);
		Range range4 = new Range(2,5);
		Range range5 = new Range(1,2);
		Range range6 = new Range(3,6);
		
		assertEquals(RangesRelation.notCrossed , range1.relation(range3));
		assertEquals(RangesRelation.inner , range1.relation(range2));
		assertEquals(RangesRelation.outer, range2.relation(range1));
		assertEquals(RangesRelation.crossed, range4.relation(range1));
		assertEquals(RangesRelation.crossed, range1.relation(range4));
		assertFalse(range1.match(0));
		assertTrue(range1.match(1));
		assertTrue(range1.match(2));
		assertFalse(range1.match(3));
		
		assertTrue(range1.smallerThan(3));
		assertFalse(range1.smallerThan(2));
		assertTrue(range1.biggerThan(0));
		
		assertTrue(range1.smallerThan(range6));
		assertTrue(range6.biggerThan(range1));
		
		SortedSet<Range> ranges = new TreeSet<Range>();
		ranges.add(range1);
		ranges.add(range2);
		ranges.add(range3);
		ranges.add(range4);
		ranges.add(range5);
		
		Iterator<Range> iterator = ranges.iterator();
		assertEquals(range3 , iterator.next());
		assertEquals(range5 , iterator.next());
		assertEquals(range1 , iterator.next());
		assertEquals(range2 , iterator.next());
		assertEquals(range4 , iterator.next());
		
	}

}
