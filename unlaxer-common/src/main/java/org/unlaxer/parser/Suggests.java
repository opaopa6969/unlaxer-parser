package org.unlaxer.parser;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public class Suggests implements List<Suggest>{
	
	List<Suggest> suggests;

	public Suggests(List<Suggest> suggests) {
		super();
		this.suggests = suggests;
	}

	@Override
	public int size() {
		return suggests.size();
	}

	@Override
	public boolean isEmpty() {
		return suggests.isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		return suggests.contains(o);
	}

	@Override
	public Iterator<Suggest> iterator() {
		return suggests.iterator();
	}

	@Override
	public Object[] toArray() {
		return suggests.toArray();
	}

	@SuppressWarnings("hiding")
	@Override
	public <Suggest> Suggest[] toArray(Suggest[] a) {
		return suggests.toArray(a);
	}

	@Override
	public boolean add(Suggest e) {
		return suggests.add(e);
	}

	@Override
	public boolean remove(Object o) {
		return suggests.remove(o);
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		return suggests.containsAll(c);
	}

	@Override
	public boolean addAll(Collection<? extends Suggest> c) {
		return suggests.addAll(c);
	}

	@Override
	public boolean addAll(int index, Collection<? extends Suggest> c) {
		return suggests.addAll(index, c);
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		return suggests.removeAll(c);
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		return suggests.retainAll(c);
	}

	@Override
	public void clear() {
		suggests.clear();
	}

	@Override
	public boolean equals(Object o) {
		return suggests.equals(o);
	}

	@Override
	public int hashCode() {
		return suggests.hashCode();
	}

	@Override
	public Suggest get(int index) {
		return suggests.get(index);
	}

	@Override
	public Suggest set(int index, Suggest element) {
		return suggests.set(index, element);
	}

	@Override
	public void add(int index, Suggest element) {
		suggests.add(index, element);
	}

	@Override
	public Suggest remove(int index) {
		return suggests.remove(index);
	}

	@Override
	public int indexOf(Object o) {
		return suggests.indexOf(o);
	}

	@Override
	public int lastIndexOf(Object o) {
		return suggests.lastIndexOf(o);
	}

	@Override
	public ListIterator<Suggest> listIterator() {
		return suggests.listIterator();
	}

	@Override
	public ListIterator<Suggest> listIterator(int index) {
		return suggests.listIterator(index);
	}

	@Override
	public List<Suggest> subList(int fromIndex, int toIndex) {
		return suggests.subList(fromIndex, toIndex);
	}

}