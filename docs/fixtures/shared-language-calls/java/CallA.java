package example.shared;
import java.util.Map;
import org.unlaxer.source.*;
import org.unlaxer.source.LanguageRegions.Language;
public class CallA extends SharedGrammarCalls.Adapter {
    public static final Language A = new Language("a", "example/a", "1", "ChildA", "Root");
    public static final Language B = new Language("b", "example/b", "1", "ChildB", "Root");
    public static final SharedGrammarCalls.Registry REGISTRY = new SharedGrammarCalls.Registry(Map.of(A, ChildAParsers.embeddedGrammar(), B, ChildBParsers.embeddedGrammar()));
    protected SharedGrammarCalls.Registry registry() { return REGISTRY; }
    protected Language language() { return A; }
}
