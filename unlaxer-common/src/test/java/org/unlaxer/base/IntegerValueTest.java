package org.unlaxer.base;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IntegerValueTest {
  
  static class FooIntegerValue extends IntegerValue<FooIntegerValue>{

    public FooIntegerValue(int value) {
      super(value);
    }

    public FooIntegerValue(IntegerValue<?> value) {
      super(value);
    }

    @Override
    public FooIntegerValue create(int i) {
      
      return new FooIntegerValue(i);
    }

    @Override
    public FooIntegerValue create(IntegerValue<?> i) {
      return new FooIntegerValue(i);
    }
    
  }

  @Test
  public void test() {
    
    
    FooIntegerValue fooIntegerValue = new FooIntegerValue(10);
    
    FooIntegerValue createIfMatch = fooIntegerValue.createIfMatch(FooIntegerValue::isPositive, ()->new FooIntegerValue(5));
    
    assertTrue(createIfMatch.eq(5));
  }

}
