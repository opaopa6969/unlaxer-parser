package org.unlaxer.parser;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Supplier;

public class Parsers implements List<Parser>  , Serializable{
	
	private static final long serialVersionUID = -5938245876985128589L;
	
	List<Parser> parsers;
	List<Supplier<? extends Parser>> parserSuppliers;
	
	public Parsers(List<Class<? extends Parser>> parsers) {
		super();
		parserSuppliers = new ArrayList<Supplier<? extends Parser>>();
		parsers.forEach(clazz->{
			Supplier<? extends Parser> supplier = Parser.getSupplier(clazz);
			parserSuppliers.add(supplier);
			
		});
	}
	
	@SafeVarargs
	public Parsers(Supplier<Parser>... parsers) {
		super();
		this.parserSuppliers = new ArrayList<>();
		for (Supplier<Parser> supplier : parsers) {
			this.parserSuppliers.add(supplier);
		}
	}
	
	@SafeVarargs
	public Parsers(Class<? extends Parser>... parsers) {
		super();
		this.parserSuppliers = new ArrayList<>();
		for (Class<? extends Parser> clazz : parsers) {
			this.parserSuppliers.add(Parser.getSupplier(clazz));
		}
	}
	
	public Parsers(Parser... parsers) {
		super();
		this.parsers = new ArrayList<>();
		for (Parser parser : parsers) {
			this.parsers.add(parser);
		}
	}

	
	public Parsers() {
		this(new ArrayList<>());
	}
	
	synchronized void  prepare(){
		if(parsers != null) {
			return;
		}
		parsers = new ArrayList<Parser>();
		for (Supplier<? extends Parser> supplier : parserSuppliers) {
			Parser parser = supplier.get();
			this.parsers.add(parser);
		}
	}
	
	public static Parsers of(List<Parser> parsers) {
		Parsers _parsers = new Parsers();
		_parsers.addAll(parsers);
		return _parsers;
	}
	
	public static Parsers of(Parser... parsers) {
		Parsers _parsers = new Parsers();
		for (Parser parser : parsers) {
			_parsers.add(parser);
		}
		return _parsers;
	}


	@Override
	public int size() {
		prepare();
		return parsers.size();
	}

	@Override
	public boolean isEmpty() {
		prepare();
		return parsers.isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		prepare();
		return parsers.contains(o);
	}

	public Iterator<Parser> iterator() {
		prepare();
		return parsers.iterator();
	}

	@Override
	public Object[] toArray() {
		prepare();
		return parsers.toArray();
	}

	@Override
	public <T> T[] toArray(T[] a) {
		prepare();
		return parsers.toArray(a);
	}

	@Override
	public boolean add(Parser e) {
		prepare();
		return parsers.add(e);
	}
	
	public boolean add(Class<? extends Parser> clazz) {
		prepare();
		return parsers.add(Parser.get(clazz));
	}

	@Override
	public boolean remove(Object o) {
		prepare();
		return parsers.remove(o);
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		prepare();
		return parsers.containsAll(c);
	}

	@Override
	public boolean addAll(Collection<? extends Parser> c) {
		prepare();
		return parsers.addAll(c);
	}

	@Override
	public boolean addAll(int index, Collection<? extends Parser> c) {
		prepare();
		return parsers.addAll(index, c);
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		prepare();
		return parsers.removeAll(c);
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		prepare();
		return parsers.retainAll(c);
	}

	@Override
	public void clear() {
		prepare();
		parsers.clear();
	}

	@Override
	public boolean equals(Object o) {
		prepare();
		return parsers.equals(o);
	}

	@Override
	public int hashCode() {
		prepare();
		return parsers.hashCode();
	}

	@Override
	public Parser get(int index) {
		prepare();
		return parsers.get(index);
	}

	@Override
	public Parser set(int index, Parser element) {
		prepare();
		return parsers.set(index, element);
	}

	@Override
	public void add(int index, Parser element) {
		prepare();
		parsers.add(index, element);
	}
	
	public Parser set(int index, Class<? extends Parser> element) {
		prepare();
		return parsers.set(index, Parser.get(element));
	}

	public void add(int index, Class<? extends Parser> element) {
		prepare();
		parsers.add(index, Parser.get(element));
	}

	@Override
	public Parser remove(int index) {
		prepare();
		return parsers.remove(index);
	}


	@Override
	public int indexOf(Object o) {
		prepare();
		return parsers.indexOf(o);
	}

	@Override
	public int lastIndexOf(Object o) {
		prepare();
		return parsers.lastIndexOf(o);
	}

	@Override
	public ListIterator<Parser> listIterator() {
		prepare();
		return parsers.listIterator();
	}

	@Override
	public ListIterator<Parser> listIterator(int index) {
		prepare();
		return parsers.listIterator(index);
	}

	@Override
	public List<Parser> subList(int fromIndex, int toIndex) {
		prepare();
		return parsers.subList(fromIndex, toIndex);
	}
	
	public List<Parser> list(){
		prepare();
		return parsers;
	}
}