package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.BeforeClass;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * 生成された LSP サーバが、非BMP文字を含むドキュメントで offset を正しく変換するか。
 *
 * <p>パーサ実行時の offset は **code point** 単位、LSP の {@code Position} は
 * **UTF-16 code unit** 単位である。{@code LSPServerEmitter} は
 * {@code codePointOffsetToStringOffset} で変換してから range を組み立てるが、
 * その変換が抜けると 😀 のようなサロゲートペア 1 個につき診断位置が 1 文字ずつ手前にずれる。
 * DAP 側は {@link DAPNonBmpLineMappingTest} が同じ罠を押さえているので、LSP 側にも同じ杭を打つ。
 *
 * <p>ubnfc の 4 実装クロスチェックで、手書きの旧 UBNF 拡張に UTF-16 / code point の
 * 取り違えが見つかっている（生成側は先に直っている）。ここはその「直っている側」を固定する。
 * bootstrap パーサ自身の非BMP は仕様由来オラクル
 * （{@code spec-corpus/ubnf/token-declarations.json} の {@code token/negation-codepoint-set}、
 * 期待値 {@code ["Negation","N","x😀"]}）が Java / Rust の両方で押さえている。
 */
public class LSPNonBmpOffsetTest {

    private static final String PACKAGE = "org.unlaxer.lspnonbmp.generated";

    /**
     * 非BMP文字そのものを受理する文法。そうしないと「😀 を消費した後ろで失敗する」状況が作れない。
     * {@code CHAR_RANGE} は BMP の非サロゲート 1 文字しか取れないので {@code NEGATION} を使う。
     */
    private static final String GRAMMAR = """
        grammar Emoji {
          @package: %s

          token INNER = NEGATION('>')

          @root
          Root ::= '<' { INNER } '>' '!' ;
        }
        """.formatted(PACKAGE);

    private static Class<?> serverClass;
    private static Class<?> concreteClass;

    @BeforeClass
    public static void setUp() throws Exception {
        var grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
        List<CodeGenerator.GeneratedSource> generated = List.of(
            new ParserGenerator().generate(grammar), new LSPGenerator().generate(grammar));
        Path out = Files.createTempDirectory("lsp-nonbmp");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StringWriter diagnostics = new StringWriter();
        boolean ok;
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            ok = compiler.getTask(new PrintWriter(diagnostics), manager, null,
                List.of("--release", "17",
                    "-classpath", System.getProperty("java.class.path"), "-d", out.toString()),
                null, java.util.stream.Stream.concat(
                    generated.stream().map(LSPNonBmpOffsetTest::toJavaFileObject),
                    java.util.stream.Stream.of(concreteServerSource())).toList()).call();
        }
        assertTrue("生成した LSP サーバがコンパイルできない:\n" + diagnostics, ok);
        URLClassLoader loader = new URLClassLoader(new URL[] {out.toUri().toURL()},
            LSPNonBmpOffsetTest.class.getClassLoader());
        // 生成されるのは abstract class なので、実体化のために空の具象クラスを添える。
        serverClass = loader.loadClass(PACKAGE + ".EmojiLanguageServer");
        concreteClass = loader.loadClass(PACKAGE + ".ConcreteEmojiLanguageServer");
    }

    private static JavaFileObject concreteServerSource() {
        String path = "/" + PACKAGE.replace('.', '/') + "/ConcreteEmojiLanguageServer.java";
        String body = "package " + PACKAGE + ";\n"
            + "public class ConcreteEmojiLanguageServer extends EmojiLanguageServer {}\n";
        return new SimpleJavaFileObject(URI.create("string://" + path), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return body;
            }
        };
    }

    @Test
    public void codePointOffsetToStringOffsetSkipsOverSurrogatePairs() throws Exception {
        Object server = concreteClass.getDeclaredConstructor().newInstance();
        String content = "x😀😀y";
        assertEquals("前提: UTF-16 では 6 単位", 6, content.length());
        assertEquals("前提: code point では 4 個", 4, content.codePointCount(0, content.length()));

        Method convert = method("codePointOffsetToStringOffset", String.class, int.class);
        assertEquals(0, convert.invoke(server, content, 0));
        assertEquals(1, convert.invoke(server, content, 1));
        assertEquals("2 個目の 😀 の手前", 3, convert.invoke(server, content, 2));
        assertEquals("'y' の手前", 5, convert.invoke(server, content, 3));
        // 範囲外はクランプされる（生成コードの Math.max/min）。
        assertEquals(6, convert.invoke(server, content, 99));
        assertEquals(0, convert.invoke(server, content, -1));
    }

    @Test
    public void offsetToPositionCountsUtf16UnitsLikeTheLspSpec() throws Exception {
        Object server = concreteClass.getDeclaredConstructor().newInstance();
        String content = "x😀\ny😀y";
        Method toPosition = method("offsetToPosition", String.class, int.class);

        assertEquals("0:0", format(toPosition.invoke(server, content, 0)));
        assertEquals("サロゲートペアは 2 単位", "0:3", format(toPosition.invoke(server, content, 3)));
        assertEquals("改行の次", "1:0", format(toPosition.invoke(server, content, 4)));
        assertEquals("2 行目の 😀 の後ろ", "1:3", format(toPosition.invoke(server, content, 7)));
    }

    @Test
    public void parseFailureAfterNonBmpReportsAUtf16Offset() throws Exception {
        Object server = concreteClass.getDeclaredConstructor().newInstance();
        Method parseDocument = concreteClass.getMethod("parseDocument", String.class, String.class);

        // 受理される: '<' 😀😀 '>' '!'。
        Object accepted = parseDocument.invoke(server, "file:///ok", "<😀😀>!");
        assertEquals(Boolean.TRUE, record(accepted, "succeeded"));

        // 失敗する: '!' の代わりに '?'。'?' は code point では offset 4、UTF-16 では offset 6。
        String broken = "<😀😀>?";
        assertEquals(7, broken.length());
        assertEquals(5, broken.codePointCount(0, broken.length()));
        Object failed = parseDocument.invoke(server, "file:///ng", broken);
        assertEquals(Boolean.FALSE, record(failed, "succeeded"));
        int errorOffset = (int) record(failed, "errorOffset");
        assertEquals("code point offset をそのまま返すと 4 になる", 6, errorOffset);
        assertEquals("ずれた offset で切り出すと 😀 の片割れが混ざる",
            "?", broken.substring(errorOffset));

        Method toPosition = method("offsetToPosition", String.class, int.class);
        assertEquals("0:6", format(toPosition.invoke(server, broken, errorOffset)));
    }

    // =========================================================================

    private static Object record(Object parseResult, String component) throws Exception {
        return parseResult.getClass().getMethod(component).invoke(parseResult);
    }

    private static String format(Object position) throws Exception {
        Method line = position.getClass().getMethod("getLine");
        Method character = position.getClass().getMethod("getCharacter");
        return line.invoke(position) + ":" + character.invoke(position);
    }

    private static Method method(String name, Class<?>... parameters) throws Exception {
        Method found = serverClass.getDeclaredMethod(name, parameters);
        found.setAccessible(true);
        return found;
    }

    private static JavaFileObject toJavaFileObject(CodeGenerator.GeneratedSource source) {
        String path = "/" + source.packageName().replace('.', '/') + "/" + source.className() + ".java";
        return new SimpleJavaFileObject(URI.create("string://" + path), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source.source();
            }
        };
    }
}
