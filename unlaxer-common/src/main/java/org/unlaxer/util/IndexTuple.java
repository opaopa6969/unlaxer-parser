package org.unlaxer.util;

import java.util.HashMap;
import java.util.Map;

import org.unlaxer.Range;
import org.unlaxer.SortedRanges;
import org.unlaxer.util.collection.ID;

public class IndexTuple {
	
	Map<ID, SortedRanges> rangesByID;
//	Map<ID, Map<NavigableSet<Range>> rangesByID;

	public IndexTuple() {
		super();
		rangesByID = new HashMap<ID, SortedRanges>();
	}
	
	public void put(ID id , Range range) {
		
		SortedRanges ranges = rangesByID.computeIfAbsent(id, _id-> new SortedRanges());
		ranges.add(range);
	}
}
