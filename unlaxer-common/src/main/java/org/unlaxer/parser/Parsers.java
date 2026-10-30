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
	
	/**
	 * The backing list, read under this lock (#1007): {@link #appendByCopy} replaces the list under
	 * the same lock, so a reader on another thread gets either the earlier list, which is no longer
	 * changed, or the complete new one.
	 */
	synchronized List<Parser> prepare(){
		if(parsers != null) {
			return parsers;
		}
		parsers = new ArrayList<Parser>();
		for (Supplier<? extends Parser> supplier : parserSuppliers) {
			Parser parser = supplier.get();
			this.parsers.add(parser);
		}
		return parsers;
	}
	
	/**
	 * Appends a parser to a list that other threads may be reading (#1007): AbstractParser attaches its
	 * lazily created delegate this way. The other mutators change the list in place and keep the
	 * single-thread contract of graph mutation.
	 */
	synchronized void appendByCopy(Parser parser){
		List<Parser> appended = new ArrayList<>(prepare());
		appended.add(parser);
		parsers = appended;
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
		return prepare().size();
	}

	@Override
	public boolean isEmpty() {
		return prepare().isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		return prepare().contains(o);
	}

	public Iterator<Parser> iterator() {
		return prepare().iterator();
	}

	@Override
	public Object[] toArray() {
		return prepare().toArray();
	}

	@Override
	public <T> T[] toArray(T[] a) {
		return prepare().toArray(a);
	}

	@Override
	public boolean add(Parser e) {
		return prepare().add(e);
	}
	
	public boolean add(Class<? extends Parser> clazz) {
		return prepare().add(Parser.get(clazz));
	}

	@Override
	public boolean remove(Object o) {
		return prepare().remove(o);
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		return prepare().containsAll(c);
	}

	@Override
	public boolean addAll(Collection<? extends Parser> c) {
		return prepare().addAll(c);
	}

	@Override
	public boolean addAll(int index, Collection<? extends Parser> c) {
		return prepare().addAll(index, c);
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		return prepare().removeAll(c);
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		return prepare().retainAll(c);
	}

	@Override
	public void clear() {
		prepare().clear();
	}

	@Override
	public boolean equals(Object o) {
		return prepare().equals(o);
	}

	@Override
	public int hashCode() {
		return prepare().hashCode();
	}

	@Override
	public Parser get(int index) {
		return prepare().get(index);
	}

	@Override
	public Parser set(int index, Parser element) {
		return prepare().set(index, element);
	}

	@Override
	public void add(int index, Parser element) {
		prepare().add(index, element);
	}
	
	public Parser set(int index, Class<? extends Parser> element) {
		return prepare().set(index, Parser.get(element));
	}

	public void add(int index, Class<? extends Parser> element) {
		prepare().add(index, Parser.get(element));
	}

	@Override
	public Parser remove(int index) {
		return prepare().remove(index);
	}


	@Override
	public int indexOf(Object o) {
		return prepare().indexOf(o);
	}

	@Override
	public int lastIndexOf(Object o) {
		return prepare().lastIndexOf(o);
	}

	@Override
	public ListIterator<Parser> listIterator() {
		return prepare().listIterator();
	}

	@Override
	public ListIterator<Parser> listIterator(int index) {
		return prepare().listIterator(index);
	}

	@Override
	public List<Parser> subList(int fromIndex, int toIndex) {
		return prepare().subList(fromIndex, toIndex);
	}
	
	public List<Parser> list(){
		return prepare();
	}
}