package org.unlaxer;

import java.util.ArrayList;
import java.util.List;

import org.unlaxer.context.ParseContext;

public class TestResult{
	
	public final Parsed parsed;
	public final ParseContext parseContext;
	public final Source lastToken;
	public final List<Boolean> assertValues;

	
	public TestResult(Parsed parsed, ParseContext parseContext, Source lastToken  , List<Boolean> assertValues) {
		super();
		this.parsed = parsed;
		this.parseContext = parseContext;
		this.lastToken = lastToken;
		this.assertValues = assertValues;
	}
	
	
	public TestResult(Parsed parsed, ParseContext parseContext, Source lastToken  , Boolean...  assertValues) {
		super();
		this.parsed = parsed;
		this.parseContext = parseContext;
		this.lastToken = lastToken;
		this.assertValues = List.of(assertValues);
	}
	
	public TestResult(Parsed parsed, ParseContext parseContext, Source lastToken) {
		super();
		this.parsed = parsed;
		this.parseContext = parseContext;
		this.lastToken = lastToken;
		this.assertValues = new ArrayList<>();
	}
	
	public void add(boolean assertValue) {
		
		this.assertValues.add(assertValue);
	}
	
	public boolean isOK() {
		
		boolean allMatch = assertValues.stream()
			.allMatch(x->x);
		return allMatch;
	}
	
	public boolean  isNG() {
		return false == isOK();
	}
}