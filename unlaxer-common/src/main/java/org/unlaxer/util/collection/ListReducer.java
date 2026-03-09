package org.unlaxer.util.collection;

import java.util.List;

public interface ListReducer<T> {
	public T reduceList(List<T> list);
}