package org.unlaxer.util.cache;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class NameAndTags {
	
	public final String name;
	public final Tags tags;
	public NameAndTags(String name, Tags tags) {
		super();
		this.name = name;
		this.tags = tags;
	}
	
	public NameAndTags(String name , Tag...tags) {
		this.name = name;
		this.tags = new Tags(tags);
	}
	
	public NameAndTags(String name , Collection<Tag> tags) {
		this.name = name;
		this.tags = new Tags(tags);
	}
	
	public static NameAndTags of(Object object) {
		return new NameAndTags(object.getClass().getName()+"@"+String.valueOf(object.hashCode()) , Collections.emptySet());
	}

	public static NameAndTags of(String name) {
		return new NameAndTags(name , Collections.emptySet());
	}
	
	public static NameAndTags of(String name , String... tags) {
		return new NameAndTags(name , Stream.of(tags).map(Tag::rightOf).collect(Collectors.toSet()));
	}
	
	public static NameAndTags of(String name , Enum<?>... tags) {
		return new NameAndTags(name , Stream.of(tags).map(Tag::leftOf).collect(Collectors.toSet()));
	}
}