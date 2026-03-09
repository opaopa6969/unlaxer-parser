package org.unlaxer.listener;

public interface LogOutputCountListener {
	
	public void onOutput(int count) ;
	
	public static final LogOutputCountListener BlackHole = new LogOutputCountListener() {
		
		@Override
		public void onOutput(int count) {}
	};
}