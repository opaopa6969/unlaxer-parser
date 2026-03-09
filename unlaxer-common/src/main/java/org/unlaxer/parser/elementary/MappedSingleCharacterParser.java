package org.unlaxer.parser.elementary;

import java.util.Arrays;
import java.util.function.Supplier;

import org.unlaxer.Name;
import org.unlaxer.Range;
import org.unlaxer.parser.StaticParser;

public class MappedSingleCharacterParser extends SingleCharacterParser implements StaticParser{

	private static final long serialVersionUID = -3810093931655394503L;

	public boolean[] matches;

	public boolean doInvert;

	public MappedSingleCharacterParser(boolean doInvert, boolean[] matches) {
		super();
		this.matches = matches;
		this.doInvert = doInvert;
	}

	public MappedSingleCharacterParser(Name name, boolean doInvert, boolean[] matches) {
		super(name);
		this.matches = matches;
		this.doInvert = doInvert;
	}

	public MappedSingleCharacterParser(boolean[] matches) {
		this(false, matches);
	}

	public MappedSingleCharacterParser(Name name, boolean[] matches) {
		this(name, false, matches);
	}

	public MappedSingleCharacterParser(char... matches) {
		this(false, matches);

	}

	public MappedSingleCharacterParser(Name name, char... matches) {
		this(name, false, matches);
	}

	public MappedSingleCharacterParser(boolean doInvert, char... matches) {
		this(null,doInvert,matches);
	}

	public MappedSingleCharacterParser(Name name, boolean doInvert, char... matches) {
		super(name);
		this.doInvert = doInvert;
		this.matches = new boolean[128];
		set(true, this.matches, matches);
	}

	public MappedSingleCharacterParser(Name name , boolean doInvert, String matches) {
		this(name , doInvert, matches.toCharArray());
	}
	
	public MappedSingleCharacterParser(boolean doInvert, String matches) {
		this(doInvert, matches.toCharArray());
	}

	public MappedSingleCharacterParser(String matches) {
		this(false, matches);
	}
	
	public MappedSingleCharacterParser(Name name ,String matches) {
		this(name , false, matches);
	}


	static Supplier<RuntimeException> charMustBeLessThan128 = () -> new IllegalArgumentException(
			"char must be less than 128");

	private static void set(boolean setFlag, boolean[] flags, char... matches) {
		for (char c : matches) {
			if (c > 128) {
				throw charMustBeLessThan128.get();
			}
			flags[c] = setFlag;
		}
	}

	public MappedSingleCharacterParser(Range... matches) {
		this(false, matches);
	}

	public MappedSingleCharacterParser(boolean doInvert, Range... matches) {
		super();
		this.doInvert = doInvert;
		this.matches = new boolean[128];
		set(true, this.matches, matches);
	}

	private void set(boolean setFlag, boolean[] flags, Range... matches) {
		for (Range range : matches) {
			for (int c = range.startIndexInclusive; c <= range.endIndexExclusive; c++) {
				if (c > 128) {
					throw charMustBeLessThan128.get();
				}
				flags[c] = setFlag;
			}
		}
	}

	@Override
	public boolean isMatch(char target) {
		return doInvert ^ target > 127 ? false : matches[target];
	}

	public MappedSingleCharacterParser newWithout(String matches) {
		return newWithout(matches.toCharArray());
	}

	public MappedSingleCharacterParser newWithout(char... matches) {
		boolean[] newMatches = Arrays.copyOf(this.matches, this.matches.length);
		set(false, newMatches, matches);
		return new MappedSingleCharacterParser(doInvert, newMatches);
	}

	public MappedSingleCharacterParser newWithout(Name name, char... matches) {
		boolean[] newMatches = Arrays.copyOf(this.matches, this.matches.length);
		set(false, newMatches, matches);
		return new MappedSingleCharacterParser(name, doInvert, newMatches);
	}

	public MappedSingleCharacterParser newWithout(Range... matches) {
		boolean[] newMatches = Arrays.copyOf(this.matches, this.matches.length);
		set(false, newMatches, matches);
		return new MappedSingleCharacterParser(doInvert, newMatches);
	}

	public MappedSingleCharacterParser newWithout(Name name, Range... matches) {
		boolean[] newMatches = Arrays.copyOf(this.matches, this.matches.length);
		set(false, newMatches, matches);
		return new MappedSingleCharacterParser(doInvert, newMatches);
	}
	
	public MappedSingleCharacterParser newWith(String matches) {
		return newWith(matches.toCharArray());
	}

	public MappedSingleCharacterParser newWith(char... matches) {
		boolean[] newMatches = Arrays.copyOf(this.matches, this.matches.length);
		set(true, newMatches, matches);
		return new MappedSingleCharacterParser(doInvert, newMatches);
	}

	public MappedSingleCharacterParser newWith(Name name, char... matches) {
		boolean[] newMatches = Arrays.copyOf(this.matches, this.matches.length);
		set(true, newMatches, matches);
		return new MappedSingleCharacterParser(name, doInvert, newMatches);
	}

	public MappedSingleCharacterParser newWith(Range... matches) {
		boolean[] newMatches = Arrays.copyOf(this.matches, this.matches.length);
		set(true, newMatches, matches);
		return new MappedSingleCharacterParser(doInvert, newMatches);
	}

	public MappedSingleCharacterParser newWith(Name name, Range... matches) {
		boolean[] newMatches = Arrays.copyOf(this.matches, this.matches.length);
		set(true, newMatches, matches);
		return new MappedSingleCharacterParser(doInvert, newMatches);
	}


}