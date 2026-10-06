import java.nio.file.*;
import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFModuleLoader;
import org.unlaxer.dsl.codegen.*;

/** Build helper; the tutorial runs generated parsers, not a handwritten substitute. */
public class Generate {
    public static void main(String[] args) throws Exception {
        var grammar = UBNFModuleLoader.load(Path.of(args[0])).grammars().get(0);
        Path output = Files.createDirectories(Path.of(args[1]));
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            var generated = generator.generate(grammar);
            Files.writeString(output.resolve(generated.className() + ".java"), generated.source());
        }
    }
}
