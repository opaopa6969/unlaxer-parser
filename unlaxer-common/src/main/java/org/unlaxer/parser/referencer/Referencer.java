package org.unlaxer.parser.referencer;

import java.io.Serializable;
import java.util.Optional;
import java.util.function.Predicate;

import org.unlaxer.parser.Parser;

public interface Referencer extends Serializable {
	
	public Optional<Parser> getReference(Parser sourceParser);
	
	
	public static abstract class AbstractReference implements Referencer{

		private static final long serialVersionUID = -3800282607148396928L;
		
		Predicate<Parser> predicate;
		
		
		public AbstractReference(Predicate<Parser> predicate) {
			this.predicate = predicate;
		}

		
		@Override
		public Optional<Parser> getReference(Parser sourceParser) {
			return sourceParser.findFirstFromRoot(predicate);
		}
	}

}
