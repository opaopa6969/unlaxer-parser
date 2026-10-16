import java.nio.file.*;
import java.util.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.*;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Build inputs only: no parser, mapper, or facade fallback is executed here. */
public final class Prepare {
    public static void main(String[] args) throws Exception {
        var grammar = UBNFMapper.parse(Files.readString(Path.of(args[0]))).grammars().get(0);
        GrammarValidator.validateOrThrow(grammar);
        Path output = Path.of(args[1]);
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(),
                new MapperGenerator(), new EvaluatorGenerator())) {
            var file = generator.generate(grammar);
            Path path = output.resolve("java-src").resolve(file.packageName().replace('.', '/'))
                    .resolve(file.className() + ".java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.source());
        }
        for (var file : new RustBackend().generate(grammar)) {
            Path path = output.resolve("rust-java/generated").resolve(file.relativePath());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.content());
        }
    }
}
