package org.unlaxer;

import org.unlaxer.base.IntegerValue;

public class TokenIndex extends IntegerValue<TokenIndex>{

  public TokenIndex(int value) {
    super(value);
  }
  
  public TokenIndex(IntegerValue<?> value) {
    super(value);
  }

  @Override
  public TokenIndex create(int i) {
    return new TokenIndex(i);
  }

  @Override
  public TokenIndex create(IntegerValue<?> i) {
    return new TokenIndex(i);
  }
  
}