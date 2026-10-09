package org.unlaxer.dsl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.unlaxer.source.LanguageProfile;

final class LanguageProfileCommand {
    private LanguageProfileCommand() {}
    static int run(String[] args, PrintStream out, PrintStream err) {
        String usage = "Usage: profile --file <profile.tsv>";
        if (args.length == 2 && args[1].equals("--help")) { out.println(usage); return 0; }
        if (args.length != 3 || !args[1].equals("--file") || args[2].isEmpty()) { err.println(usage); return 2; }
        try { out.print(LanguageProfile.parse(Files.readString(Path.of(args[2]))).canonicalTsv()); return 0; }
        catch (IOException error) { err.println(error.getMessage()); return 4; }
        catch (IllegalArgumentException error) { err.println(error.getMessage()); return 3; }
    }
}
