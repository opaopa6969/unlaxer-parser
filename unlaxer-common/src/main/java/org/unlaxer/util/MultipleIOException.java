package org.unlaxer.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class MultipleIOException extends IOException{

	private static final long serialVersionUID = -5674203269195564149L;
	
	public final List<Exception> causes;

	public MultipleIOException(List<Exception> causes) {
		super();
		this.causes = causes;
	}
	
	public MultipleIOException(String message , List<Exception> causes) {
		super(message);
		this.causes = causes;
	}
	
	public MultipleIOException() {
		super();
		this.causes = new ArrayList<>();
	}
	
	public MultipleIOException(String message) {
		super(message);
		this.causes = new ArrayList<>();
	}
	
	public static <T> Optional<MultipleIOException> process(
			Collection<T> collection,
			Consumer<T> collectionConsumer) {

		List<Exception> exceptions = new ArrayList<Exception>();
		for (T current : collection){
			try{
				collectionConsumer.accept(current);
			}catch(Exception e){
				exceptions.add(e);
			}
		}
		return exceptions.isEmpty() ?
				Optional.empty():
				Optional.of(new MultipleIOException(exceptions));
	}

}