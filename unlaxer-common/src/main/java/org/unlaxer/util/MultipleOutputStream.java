package org.unlaxer.util;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class MultipleOutputStream extends OutputStream {

	private Collection<OutputStream> outputs;

	public MultipleOutputStream(Collection<OutputStream> outputs) {

		this.outputs = outputs;
	}
	
	public MultipleOutputStream(OutputStream... outputs) {

		this.outputs = Arrays.asList(outputs);
	}


	@Override
	public void write(int value) throws IOException {

		Optional<MultipleIOException> process = process(out->{
			try {
				out.write(value);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		if(process.isPresent()){throw process.get();}
	}

	@Override
	public void write(byte[] value) throws IOException {
		
		Optional<MultipleIOException> process = process(out->{
			try {
				out.write(value);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		if(process.isPresent()){throw process.get();}
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {

		Optional<MultipleIOException> process = process(out->{
			try {
				out.write(b, off, len);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		if(process.isPresent()){throw process.get();}

	}

	@Override
	public void close() throws IOException {

		Optional<MultipleIOException> process = process(out->{
			try {
				out.close();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		if(process.isPresent()){throw process.get();}
	}

	@Override
	public void flush() throws IOException {

		Optional<MultipleIOException> process = process(out->{
			try {
				out.flush();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		if(process.isPresent()){throw process.get();}
	}
	
	public Optional<MultipleIOException> process(Consumer<OutputStream> outputConsumer) {

		List<Exception> exceptions = new ArrayList<Exception>();
		for (OutputStream current : outputs){
			try{
				outputConsumer.accept(current);
			}catch(Exception e){
				exceptions.add(e);
			}
		}
		return exceptions.isEmpty() ?
				Optional.empty():
				Optional.of(new MultipleIOException(exceptions));
	}

}