package org.unlaxer.dsl;

import java.io.PrintStream;
import java.nio.file.Path;
import org.unlaxer.dsl.bootstrap.UBNFPackageResolver;

/** Dependency acquisition is explicit; generate/check/playground only read verified cache. */
final class PackageCommand {
    private static final String HELP = "Usage: unlaxer deps resolve --manifest <ubnf.json> | deps inspect --grammar <file.ubnf>";
    private PackageCommand() {}
    static int run(String[] arguments, PrintStream out, PrintStream err) {
        if (arguments.length == 2 && arguments[1].equals("--help")) { out.println(HELP); return 0; }
        if (arguments.length == 4 && arguments[1].equals("inspect") && arguments[2].equals("--grammar") && !arguments[3].isEmpty()) {
            try { out.println(org.unlaxer.dsl.tooling.VocabularyOrigins.inspect(Path.of(arguments[3]))); return 0; }
            catch (Exception failure) { err.println(failure.getMessage()); return 3; }
        }
        if (arguments.length != 4 || !arguments[1].equals("resolve") || !arguments[2].equals("--manifest") || arguments[3].isEmpty()) {
            err.println(HELP); return 2;
        }
        try {
            out.println(UBNFPackageResolver.resolve(Path.of(arguments[3])));
            return 0;
        } catch (Exception exception) {
            err.println(exception.getMessage());
            return 3;
        }
    }
}
