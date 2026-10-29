package org.unlaxer.dsl;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Test-only fixture host: runs {@link CodegenMain#run} for one JSON argument vector per
 * stdin line in a single JVM and answers one JSON line ({@code exit}, {@code stdout},
 * {@code stderr}). It avoids a JVM start per fixture invocation; the process environment
 * (credentials override, HOME) is fixed by the caller and files are re-read per call.
 */
public final class CodegenBatchHost {
    private CodegenBatchHost() {}

    public static void main(String[] ignored) throws IOException {
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream originalOut = System.out, originalErr = System.err;
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null; ) {
            JsonArray vector = JsonParser.parseString(line).getAsJsonArray();
            String[] args = new String[vector.size()];
            for (int i = 0; i < args.length; i++) args[i] = vector.get(i).getAsString();
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            int exit;
            try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
                 PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
                // Stray writes to the global streams are captured with the call's own output.
                System.setOut(o); System.setErr(e);
                try { exit = CodegenMain.run(args, o, e); }
                catch (RuntimeException uncaught) {
                    // A real CLI would exit 1 with a stack trace; never echo the message.
                    exit = 1; e.println("uncaught " + uncaught.getClass().getName());
                }
                finally { System.setOut(originalOut); System.setErr(originalErr); }
            }
            JsonObject answer = new JsonObject();
            answer.addProperty("exit", exit);
            answer.addProperty("stdout", out.toString(StandardCharsets.UTF_8));
            answer.addProperty("stderr", err.toString(StandardCharsets.UTF_8));
            protocol.println(answer);
        }
    }
}
