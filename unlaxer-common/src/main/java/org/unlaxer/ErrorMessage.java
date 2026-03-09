package org.unlaxer;

public class ErrorMessage implements RangedContent<String>{
	
  CursorRange position;
	
	String message;

	public ErrorMessage(CursorRange position, String message) {
		super();
		this.position = position;
		this.message = message;
	}

	@Override
	public CursorRange getRange() {
		return position;
	}

	@Override
	public String getContent() {
		return message;
	}
}