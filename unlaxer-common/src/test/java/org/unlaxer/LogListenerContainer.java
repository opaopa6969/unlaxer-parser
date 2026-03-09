package org.unlaxer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.unlaxer.parser.Parser;

public class LogListenerContainer{
	
	public final ThreadLocal<List<Predicate<Parser>>> breakPointPredicates = 
			new ThreadLocal<List<Predicate<Parser>>>(){
		
		@Override
		protected List<Predicate<Parser>> initialValue() {
			return new ArrayList<Predicate<Parser>>();
		}
	};
	
	public void addBreakPointPredicate(Predicate<Parser> predicate){
		breakPointPredicates.get().add(predicate);
	}
	
	public void clearBreakPointPredicate(){
		breakPointPredicates.get().clear();
	}
	
	public final ThreadLocal<Set<Integer>> breakPoints = 
		new ThreadLocal<Set<Integer>>(){

		@Override
		protected Set<Integer> initialValue() {
			return new HashSet<>();
		}
	};
	
	public void addLogBreakPoints(Set<Integer> breakPoints){
		this.breakPoints.get().addAll(breakPoints);
	}
	
	public void addLogBreakPoints(int... breakPoints){
		Set<Integer> targets = this.breakPoints.get();
		for(int breakPoint :  breakPoints){
			targets.add(breakPoint);
		}
	}
	
	public void clearLogBreakPoints(){
		breakPoints.get().clear();
	}
}