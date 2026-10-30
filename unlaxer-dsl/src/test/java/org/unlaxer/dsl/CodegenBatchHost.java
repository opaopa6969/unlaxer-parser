package org.unlaxer.dsl;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.Base64;

/**
 * Test-only fixture host: runs {@link CodegenMain#run} for one JSON argument vector per
 * stdin line in a single JVM and answers one JSON line ({@code exit}, {@code stdout},
 * {@code stderr}). It avoids a JVM start per fixture invocation; the process environment
 * (credentials override, HOME) is fixed by the caller and files are re-read per call.
 */
public final class CodegenBatchHost {
    private CodegenBatchHost() {}

    public static void main(String[] options) throws IOException {
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        // Updated JDK17 releases backport the JDK21 header budget. Inspect the
        // parser's field without opening the module or changing the product.
        if (options.length == 1 && options[0].equals("--http-header-limit")) {
            boolean limited;
            try {
                Class.forName("jdk.internal.net.http.Http1HeaderParser").getDeclaredField("MAX_HTTP_HEADER_SIZE");
                limited = true;
            } catch (NoSuchFieldException absent) {
                limited = false;
            } catch (ClassNotFoundException absent) {
                throw new IOException("HTTP header parser unavailable", absent);
            }
            protocol.println(limited ? "limited" : "unlimited");
            return;
        }
        // Locale fixtures cross the OS argv byte boundary and capture the same stream
        // encoders as the launcher. The UTF-8 protocol must not hide C-locale failures.
        boolean localePaths = options.length == 1 && options[0].equals("--locale-paths");
        Charset filenames = Charset.forName(System.getProperty("sun.jnu.encoding"));
        Charset stdout = localePaths ? Charset.forName(System.getProperty(
            "stdout.encoding", System.getProperty("sun.stdout.encoding", Charset.defaultCharset().name()))) : StandardCharsets.UTF_8;
        Charset stderr = localePaths ? Charset.forName(System.getProperty(
            "stderr.encoding", System.getProperty("sun.stderr.encoding", Charset.defaultCharset().name()))) : StandardCharsets.UTF_8;
        if (options.length == 1 && options[0].equals("--locale-info")) {
            JsonObject info = new JsonObject();
            info.addProperty("jdk", System.getProperty("java.specification.version"));
            info.addProperty("filenames", filenames.name());
            info.addProperty("files", Charset.defaultCharset().name());
            info.addProperty("stdout", System.getProperty("stdout.encoding", System.getProperty("sun.stdout.encoding", Charset.defaultCharset().name())));
            info.addProperty("stderr", System.getProperty("stderr.encoding", System.getProperty("sun.stderr.encoding", Charset.defaultCharset().name())));
            protocol.println(info);
            return;
        }
        PrintStream originalOut = System.out, originalErr = System.err;
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null; ) {
            JsonArray vector = JsonParser.parseString(line).getAsJsonArray();
            String[] args = new String[vector.size()];
            for (int i = 0; i < args.length; i++) args[i] = localePaths
                ? new String(Base64.getDecoder().decode(vector.get(i).getAsString()), filenames)
                : vector.get(i).getAsString();
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            int exit;
            try (PrintStream o = new PrintStream(out, true, stdout);
                 PrintStream e = new PrintStream(err, true, stderr)) {
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
            answer.addProperty("stdout", out.toString(stdout));
            answer.addProperty("stderr", err.toString(stderr));
            protocol.println(answer);
        }
    }
}
