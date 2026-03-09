package org.unlaxer.base;

public class IntegerID extends IntegerValue<IntegerID>{

	private static final long serialVersionUID = -6469191503659581274L;

	public IntegerID(int value) {
		super(value);
	}
	
	public IntegerID(IntegerValue<?> value) {
    super(value);
  }

  @Override
  public IntegerID create(int i) {
    return new IntegerID(i);
  }

  @Override
  public IntegerID create(IntegerValue<?> i) {
    return new IntegerID(i);
  }
}