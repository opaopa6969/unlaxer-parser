package org.unlaxer.util.cache;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Tags {
	
	final Set<Tag> tags;

	public Tags(Tag...tags) {
		if(tags == null) {
			this.tags = Collections.emptySet();
		}else {
			this.tags = Stream.of(tags).collect(Collectors.toSet());
		}
	}
	
	public Tags(Collection<Tag> tags) {
		this.tags = new HashSet<>(tags);
	}
	
	public boolean contains(Tag tag) {
		return tags.contains(tag);
	}
	
	public Set<Tag> get(){
		return tags;
	}
	
	public static Tags of(String... tags) {
		return new Tags(Stream.of(tags).map(Tag::rightOf).collect(Collectors.toSet()));
	}
	
	public static Tags of(Enum<?>... tags) {
		return new Tags(Stream.of(tags).map(Tag::leftOf).collect(Collectors.toSet()));
	}
	
}