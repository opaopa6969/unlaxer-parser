import java.nio.file.*;
import org.unlaxer.tinyexpression.loader.model.FormulaInfoSourceDocument;
import org.unlaxer.tinyexpression.p4.*;
/** Verifies the actual public facade independently of the native bridge's lexical extraction. */
public class Compatibility {
    public static void main(String[] args) throws Exception {
        String source=Files.readString(Path.of(args[0]));
        var document=FormulaInfoSourceDocument.parse(source);
        if(document.sections().size()!=10)throw new AssertionError("production section count");
        for(var engine:P4ParserEngine.values()) {
            int count=0;
            for(var section:document.sections()) {
                var parsed=P4ParserEngine.with(engine,()->P4PreferredAstMapper.parseDetailed(section.debugSource()));
                if(parsed.ast()==null || parsed.sourceText().spanOf(parsed.ast()).isEmpty())throw new AssertionError("owned AST missing");
                count++;
            }
            System.out.println(engine+"\t"+count+"\tPASS");
        }
    }
}
