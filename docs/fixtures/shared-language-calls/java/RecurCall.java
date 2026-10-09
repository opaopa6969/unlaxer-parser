package example.shared;
import org.unlaxer.source.*;
import org.unlaxer.source.LanguageRegions.Language;
public class RecurCall extends SharedGrammarCalls.Adapter {
 protected SharedGrammarCalls.Registry registry() { return Checks.boundary("Recur"); }
 protected Language language() { return Checks.language("Recur"); }
}
