package org.unlaxer.util;

import static org.junit.Assert.assertEquals;

import java.util.Iterator;
import java.util.NavigableSet;

import org.junit.Test;
import org.unlaxer.Range;
import org.unlaxer.SortedRanges;

public class IndexTupleTest {

	@Test
	public void test() {
		
		SortedRanges ranges = new SortedRanges();
		
		Range range0 = new Range(0,1);
		Range range1 = new Range(1,3);
		Range range2 = new Range(2,5);
		Range range3 = new Range(4,8);
		
		ranges.add(range0);
		ranges.add(range1);
		ranges.add(range2);
		ranges.add(range3);

		{
			Iterator<Range> iterator = ranges.navigableSet().iterator();
			while (iterator.hasNext()) {
				Range range = (Range) iterator.next();
				System.out.println(range);
			}
		}
		System.out.println();
		{
			
			NavigableSet<Range> headSet = ranges.navigableSet().headSet(new Range(2),true);
			Iterator<Range> iterator = headSet.iterator();
			while (iterator.hasNext()) {
				Range range = (Range) iterator.next();
				System.out.println(range);
			}
		}
		
		
		System.out.println();
		
		{
			NavigableSet<Range> matches = ranges.matches(0);
			System.out.println(matches.first());
			assertEquals("[0,1]", matches.first().toString());
		}
		{
			NavigableSet<Range> matches = ranges.matches(1);
			System.out.println(matches.first());
			assertEquals("[1,3]", matches.first().toString());
		}
		{
			NavigableSet<Range> matches = ranges.matches(2);
			System.out.println(matches.first());
			assertEquals("[1,3]", matches.first().toString());
		}
		{
			NavigableSet<Range> matches = ranges.matches(3);
			System.out.println(matches.first());
			assertEquals("[2,5]", matches.first().toString());
		}
		{
			NavigableSet<Range> matches = ranges.matches(4);
			System.out.println(matches.first());
			assertEquals("[2,5]", matches.first().toString());
		}
		{
			NavigableSet<Range> matches = ranges.matches(5);
			System.out.println(matches.first());
			assertEquals("[4,8]", matches.first().toString());
		}
		
		{
			NavigableSet<Range> matches = ranges.smallers(1);
			System.out.println(matches.last());
			assertEquals("[0,1]", matches.last().toString());
		}
		{
			NavigableSet<Range> matches = ranges.smallers(5);
			System.out.println(matches.last());
			assertEquals("[2,5]", matches.last().toString());
		}
		{
			NavigableSet<Range> matches = ranges.biggers(1);
			System.out.println(matches.first());
			assertEquals("[2,5]", matches.first().toString());
			assertEquals("[4,8]", matches.last().toString());
		}

	}

}
