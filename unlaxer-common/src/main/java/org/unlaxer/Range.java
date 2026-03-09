package org.unlaxer;

import java.util.stream.IntStream;

public class Range implements Comparable<Range>{
	
	public final int startIndexInclusive;
	public final int endIndexExclusive;
	
	 
  public Range(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive) {
    super();
    this.startIndexInclusive = startIndexInclusive.value();
    this.endIndexExclusive = endIndexExclusive.value();
  }
	
	public Range(int startIndexInclusive, int endIndexExclusive) {
		super();
		this.startIndexInclusive = startIndexInclusive;
		this.endIndexExclusive = endIndexExclusive;
	}
	public Range(int startIndexInclusive) {
		super();
		this.startIndexInclusive = startIndexInclusive;
		this.endIndexExclusive = startIndexInclusive;
	}
	
	public Range(CodePointIndex startIndexInclusive) {
    super();
    this.startIndexInclusive = startIndexInclusive.value();
    this.endIndexExclusive = startIndexInclusive.value();
  }
	
	public Range() {
		super();
		this.startIndexInclusive = 0;
		this.endIndexExclusive = 0;
	}
	
	public final int startIndexInclusive() {
		return startIndexInclusive;
	}
	
	public final int endIndexExclusive() {
		return endIndexExclusive;
	}
	
	public boolean isSingle() {
		return startIndexInclusive == endIndexExclusive;
	}

	public boolean match(int position){
		return position >=startIndexInclusive && position < endIndexExclusive;
	}
	
	public boolean smallerThan(int position){
		return position >= endIndexExclusive;
	}
	
	public boolean biggerThan(int position){
		return position < startIndexInclusive;
	}
	
	public boolean smallerThan(Range other){
		return other.startIndexInclusive >=  endIndexExclusive;
	}
	
	public boolean biggerThan(Range other){
		return other.endIndexExclusive <= startIndexInclusive;
	}
	
	public RangesRelation relation(Range other){
		int otherStart = other.startIndexInclusive;
		int otherEnd= other.endIndexExclusive;
		if(startIndexInclusive == otherStart && endIndexExclusive == otherEnd){
			
			return RangesRelation.equal;
			
		}else if(startIndexInclusive >= otherStart && endIndexExclusive <= otherEnd){
			
			return RangesRelation.outer;
			
		}else if(startIndexInclusive <= otherStart && endIndexExclusive >= otherEnd){
			
			return RangesRelation.inner;
			
		}else if(startIndexInclusive >= otherEnd || endIndexExclusive <= otherStart){
			
			return RangesRelation.notCrossed;
		}
		return RangesRelation.crossed;
	}
	
	public IntStream stream() {
		return IntStream.range(startIndexInclusive, endIndexExclusive);
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + endIndexExclusive;
		result = prime * result + startIndexInclusive;
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Range other = (Range) obj;
		if (endIndexExclusive != other.endIndexExclusive)
			return false;
		if (startIndexInclusive != other.startIndexInclusive)
			return false;
		return true;
	}
	
	
	@Override
	public int compareTo(Range other) {
		int value = startIndexInclusive - other.startIndexInclusive;
		if(value == 0) {
			return endIndexExclusive - other.endIndexExclusive;
		}
		return value;
	}
	@Override
	public String toString() {
		return isSingle() ?
			"["+startIndexInclusive+"]":
			"["+startIndexInclusive+","+endIndexExclusive+"]";
				
	}
	public static Range invalidRange() {
		return invalidRange;
	}
	
	static final Range invalidRange = new Range();
}