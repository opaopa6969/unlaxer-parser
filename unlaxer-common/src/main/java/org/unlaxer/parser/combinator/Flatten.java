package org.unlaxer.parser.combinator;



import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.unlaxer.Name;
import org.unlaxer.parser.Parser;

public class Flatten extends Chain implements List<Parser>{

	private static final long serialVersionUID = -3708311500625301059L;

	public Flatten(Name name, Parser child) {
		super(name, child.getChildren());
	}

	public Flatten(Parser child) {
		super(child.getChildren());
	}

	@Override
	public int size() {
		return getChildren().size();
	}

	@Override
	public boolean isEmpty() {
		return getChildren().isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		return getChildren().contains(o);
	}

	@Override
	public Iterator<Parser> iterator() {
		return getChildren().iterator();
	}

	@Override
	public Object[] toArray() {
		return getChildren().toArray();
	}

	@Override
	public <T> T[] toArray(T[] a) {
		return getChildren().toArray(a);
	}

	@Override
	public boolean add(Parser e) {
		return getChildren().add(e);
	}

	@Override
	public boolean remove(Object o) {
		return getChildren().remove(o);
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		return getChildren().containsAll(c);
	}

	@Override
	public boolean addAll(Collection<? extends Parser> c) {
		return getChildren().addAll(c);
	}

	@Override
	public boolean addAll(int index, Collection<? extends Parser> c) {
		return false;
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		return getChildren().removeAll(c);
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		return getChildren().retainAll(c);
	}

	@Override
	public void clear() {
		getChildren().clear();
	}

	@Override
	public Parser get(int index) {
		return getChildren().get(index);
	}

	@Override
	public Parser set(int index, Parser element) {
		return getChildren().set(index, element);
	}

	@Override
	public void add(int index, Parser element) {
		getChildren().add(index, element);
	}

	@Override
	public Parser remove(int index) {
		return getChildren().remove(index);
	}

	@Override
	public int indexOf(Object o) {
		return getChildren().indexOf(o);
	}

	@Override
	public int lastIndexOf(Object o) {
		return getChildren().lastIndexOf(o);
	}

	@Override
	public ListIterator<Parser> listIterator() {
		return getChildren().listIterator();
	}

	@Override
	public ListIterator<Parser> listIterator(int index) {
		return getChildren().listIterator(index);
	}

	@Override
	public List<Parser> subList(int fromIndex, int toIndex) {
		return getChildren().subList(fromIndex, toIndex);
	}
}