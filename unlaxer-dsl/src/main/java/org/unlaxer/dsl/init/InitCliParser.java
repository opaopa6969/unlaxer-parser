package org.unlaxer.dsl.init;

/**
 * `unlaxer init` サブコマンドの引数パーサー。
 *
 * <pre>
 * unlaxer init &lt;name&gt;
 *   [--package &lt;pkg&gt;]        default: org.example.&lt;name&gt;
 *   [--group-id &lt;gid&gt;]       default: package から導出
 *   [--extension &lt;.ext&gt;]     default: .&lt;name&gt;
 *   [--output-dir &lt;dir&gt;]     default: ./&lt;name&gt;
 *   [--from &lt;file.ubnf&gt;]     default: 内蔵サンプル文法
 *   [--with-dap]             DAP デバッグサポートを含める (要 @mapping)
 *   [--syntax-only]          syntaxes/*.tmLanguage.json のみ再生成
 *   [--force]                既存ディレクトリでもエラーにせず上書き
 * </pre>
 */
public final class InitCliParser {

    private InitCliParser() {}

    public static InitOptions parse(String[] args) throws InitUsageException {
        // args[0] は "init" がすでに剥がされている前提で呼ばれる
        if (args.length == 0) {
            throw new InitUsageException("missing required argument: <name>", true);
        }
        String name = null;
        String packageName = null;
        String groupId = null;
        String extension = null;
        String outputDir = null;
        String fromGrammar = null;
        boolean withDap = false;
        boolean syntaxOnly = false;
        boolean force = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--package" -> packageName = required(args, ++i, "--package");
                case "--group-id" -> groupId = required(args, ++i, "--group-id");
                case "--extension" -> extension = required(args, ++i, "--extension");
                case "--output-dir" -> outputDir = required(args, ++i, "--output-dir");
                case "--from" -> fromGrammar = required(args, ++i, "--from");
                case "--with-dap" -> withDap = true;
                case "--no-dap" -> withDap = false; // accepted for symmetry
                case "--syntax-only" -> syntaxOnly = true;
                case "--force" -> force = true;
                case "--help", "-h" -> throw new InitUsageException(null, true);
                default -> {
                    if (a.startsWith("-")) {
                        throw new InitUsageException("unknown init option: " + a, true);
                    }
                    if (name != null) {
                        throw new InitUsageException("only one <name> may be given (got: " + name + ", " + a + ")", true);
                    }
                    name = a;
                }
            }
        }
        if (name == null) {
            throw new InitUsageException("missing required argument: <name>", true);
        }
        return new InitOptions(name, packageName, groupId, extension, outputDir,
            fromGrammar, withDap, syntaxOnly, force);
    }

    private static String required(String[] args, int i, String flag) throws InitUsageException {
        if (i >= args.length) {
            throw new InitUsageException("missing value for " + flag, true);
        }
        return args[i];
    }

    public record InitOptions(
        String name,
        String packageName,
        String groupId,
        String extension,
        String outputDir,
        String fromGrammar,
        boolean withDap,
        boolean syntaxOnly,
        boolean force
    ) {}

    public static final class InitUsageException extends Exception {
        private final boolean showUsage;
        public InitUsageException(String message, boolean showUsage) {
            super(message);
            this.showUsage = showUsage;
        }
        public boolean showUsage() { return showUsage; }
    }
}
