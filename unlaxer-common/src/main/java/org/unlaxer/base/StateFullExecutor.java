package org.unlaxer.base;

import java.util.Set;

public interface StateFullExecutor<STATE extends State<STATE>,CONTEXT , RETURN>{
	
	public interface StateAndReturning<STATE,RETURN>{
		STATE state();
		RETURN returning();
		default boolean returningRequired() {
			
			return returning().getClass() != Void.class;
		}
	}
	
	Set<STATE> startStates();
	Set<STATE> endStates();
	
	StateAndReturning<STATE,RETURN> execute(CONTEXT context);
		
	
//	default Logger logger() {
//		return AuthLogger.logger();
//	}
	
}