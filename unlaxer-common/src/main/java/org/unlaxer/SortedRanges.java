package org.unlaxer;

import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class SortedRanges{
	
	NavigableSet<Range> ranges;
	
	public SortedRanges() {
		super();
		this.ranges = new TreeSet<Range>();
	}
	
	public NavigableSet<Range> navigableSet(){
		return ranges;
	}
	
	public Stream<Range> stream(){
		return ranges.stream();
	}

	
	public boolean add(Range range) {
		return ranges.add(range);
	}
	
	public NavigableSet<Range> matches(Predicate<Range> rangePredicate){
		
		NavigableSet<Range> matches = new TreeSet<Range>();
		stream()
			.filter(rangePredicate)
			.forEach(matches::add);
		return matches;
	}
	
	public NavigableSet<Range> matches(int index){
		
		return matches(range->range.match(index));
	}
	
	public NavigableSet<Range> smallers(int index){
		
		return matches(range->range.smallerThan(index));
	}
	
	
	public NavigableSet<Range> biggers(int index){
		
		return matches(range->range.biggerThan(index));
	}
	
}