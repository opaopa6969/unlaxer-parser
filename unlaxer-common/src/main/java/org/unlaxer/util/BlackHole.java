package org.unlaxer.util;

import java.io.IOException;
import java.io.OutputStream;

public class BlackHole {
	
	static OutputStream outputStream = new OutputStream() {
		
		@Override
		public void write(int b) throws IOException {}
	};
	
	public static OutputStream getOutputStream(){
		return outputStream;
	}

}
