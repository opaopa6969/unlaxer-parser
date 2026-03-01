package org.unlaxer;

public class Parsed extends Committed{
	
	private static final long serialVersionUID = 2547695723275359572L;
	
	public enum Status{
		succeeded,
		stopped,
		failed;
		public boolean isSucceeded(){
			return this == succeeded || this == stopped;
		}
		public boolean isStopped(){
			return this == stopped;
		}
		public boolean isFailed(){
			return this == failed;
		}
		
		public Status negate(){
			return isSucceeded() ? failed : succeeded; 
		}
	}

	public Status status;
	
	public static final Parsed FAILED = new Parsed(Status.failed);
	
	public static final Parsed STOPPED = new Parsed(Status.stopped);
	
	public static final Parsed SUCCEEDED = new Parsed(Status.succeeded);
	
	private String message; 
	
	public Parsed(Committed committed) {
		this(committed,Status.succeeded);
	}

	public Parsed(Committed committed , Status status) {
		super(committed);
		this.status = status;
	}
	
	public Parsed(TokenList originalTokens , Status status) {
		super(originalTokens);
		this.status = status;
	}

	public Parsed(Token token, TokenList originalTokens , Status status) {
		super(token, originalTokens);
		this.status = status;
	}

	public Parsed(Token token) {
		this(token,Status.succeeded);
	}
	
	public Parsed(Token token,Status status) {
		this(token,TokenList.of(token),status);
	}
	
	public Parsed(Status status) {
		super();
		this.status = status;
	}

	public Parsed negate() {
		return new Parsed(this, status.negate());
	}
	
	public boolean isSucceeded(){
		return status.isSucceeded();
	}
	public boolean isStopped(){
		return status.isStopped();
	}
	public boolean isFailed(){
		return status.isFailed();
	}
	
	public Parsed setMessage(String message) {
		this.message = message;
		return this;
	}
	
	public String getMessage() {
		return status.name() + ":" + (message == null ? "" : message);
	}
}