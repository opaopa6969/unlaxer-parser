package org.unlaxer.util;

import java.util.function.Function;
import java.util.function.Supplier;

import org.unlaxer.CodePointIndex;
import org.unlaxer.CursorRange;
import org.unlaxer.Source;
import org.unlaxer.StringSource;

public class Slicer implements Supplier<Source>{
	
	private Source word;
	
	private CodePointIndex beginIndexInclusive;
	private CodePointIndex endIndexExclusive;
	private int step;
	private Source rootSource;
	
	public static Slicer of(String rootString) {
	  return new Slicer(StringSource.createRootSource(rootString));
	}
	
	public Slicer(Source rootSource , String word) {
	  this(StringSource.createDetachedSource(word,rootSource));
	}

	public Slicer(Source word) {
		super();
		rootSource = word.root();
		this.word = word;
		beginIndexInclusive = new CodePointIndex(0);
		endIndexExclusive = this.word.endIndexExclusive();
		step=1;
	}
	
	
	/**
	 * @param beginIndexInclusive if beginIndexInclusive less than 0 then position relative from tail
	 *        (like python slice style)
	 * @return this object
	 */
	public Slicer begin(CodePointIndex beginIndexInclusive){
		this.beginIndexInclusive = beginIndexInclusive.isNegative() ?
				beginIndexInclusive.newWithPlus(word.length()):
				beginIndexInclusive;
		return this;
	}
	
	public Slicer begin(Function<Source,CodePointIndex> positionSpecifier){
		beginIndexInclusive = positionSpecifier.apply(word);
		return this;
	}
	
	/**
	 * @param endIndexExclusive if endIndexExclusive less than 0 then position relative from tail
	 *        (like python slice style)
	 * @return this object
	 */
	public Slicer end(CodePointIndex endIndexExclusive){
		this.endIndexExclusive = endIndexExclusive.isNegative() ?
				endIndexExclusive.newWithPlus(word.length()):
				endIndexExclusive;
		return this;
	}
	
	public Slicer end(Function<Source,CodePointIndex> positionSpecifier){
		endIndexExclusive = positionSpecifier.apply(word);
		return this;
	}
	
	public Slicer range(Function<Source,CursorRange> rangeSpecifier){
	  CursorRange range = rangeSpecifier.apply(word);
		begin(range.startIndexInclusive.position());
		end(range.endIndexExclusive.position());
		return this;
	}
	
	public Slicer step(int step){
		this.step = step;
		return this;
	}
	
	public Slicer invalidate(){
		begin(new CodePointIndex(0));
		end(new CodePointIndex(0));
		return this;
	}

	// TODO improve performance see AbstractStringBuilder#reverse
	@Override
	public Source get() {
		
		if(step == 1){
			
			return word.subSource(beginIndexInclusive, endIndexExclusive);
		}else if(step ==0){
			
			return Source.SUB_EMPTY.get(rootSource);
		}
		int start = step < 0 ? endIndexExclusive.value() -1 : beginIndexInclusive.value();
		int end = step < 0 ? beginIndexInclusive.value() : endIndexExclusive.value() ;
		SimpleBuilder builder = new SimpleBuilder();
		if(step < 0){
			
			for(int i = start ; i >= end ; i = i + step){
				builder.append(word.subSource(new CodePointIndex(i) ,new CodePointIndex(i+1)));
			}
		}else{
			
			for(int i = start ; i < end ; i = i + step){
				builder.append(word.subSource(new CodePointIndex(i) ,new CodePointIndex(i+1)));
			}
		}
		return builder.toSource();
	}
	
	public Source reverse() {
		return reverse(true);
	}
	
	public Source reverse(boolean reverse) {
		
		return reverse ? //
				new SimpleBuilder(get()).reverse().toSource():
				get();
	}
	
	public int length(){
		return word.length();
	}
	
	public Slicer pythonian(String colonSeparatedValue){
		
		String[] splits = colonSeparatedValue.split(":",3);
		if(splits.length>2 && false ==splits[2].isEmpty()){
			step(Integer.parseInt(splits[2]));
		}
		if(splits.length>1 && false ==splits[1].isEmpty()){
			end(new CodePointIndex(Integer.parseInt(splits[1])));
		}
		if(false ==splits[0].isEmpty()){
			begin(new CodePointIndex(Integer.parseInt(splits[0])));
		}
		return this;
	}
}