package org.unlaxer.dsl.init;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * `unlaxer init` で受け取った grammar 名から各種派生名を導出する。
 *
 * 例: 入力 "myLang" / "my-lang" / "my_lang" / "MyLang" → 同一の派生結果
 *  - {@link #lower()} → "mylang" (small caps, ファイル名/拡張機能 ID/設定キー prefix)
 *  - {@link #className()} → "MyLang" (CamelCase, Java クラス名 prefix)
 *  - {@link #upper()} → "MYLANG" (大文字, 定数用)
 *
 * パッケージ名の導出は呼び出し側で行うが、デフォルト導出として
 * {@link #defaultPackage(String)} を提供する。
 */
public final class NameDeriver {

    private static final Pattern INVALID_CHAR = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern WORD_BOUNDARY = Pattern.compile("[-_\\s]+|(?<=[a-z0-9])(?=[A-Z])");

    private final String lower;
    private final String className;
    private final String upper;

    private NameDeriver(String lower, String className, String upper) {
        this.lower = lower;
        this.className = className;
        this.upper = upper;
    }

    public static NameDeriver of(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("grammar name must be non-blank");
        }
        String trimmed = rawName.trim();
        // Must start with a letter so the result is a valid Java identifier.
        if (!Character.isLetter(trimmed.charAt(0))) {
            throw new IllegalArgumentException(
                "grammar name must start with a letter: " + rawName);
        }
        if (INVALID_CHAR.matcher(trimmed).find()
            && !trimmed.matches("[A-Za-z0-9_\\-]+")) {
            throw new IllegalArgumentException(
                "grammar name may only contain letters, digits, '-' and '_': " + rawName);
        }

        String[] words = WORD_BOUNDARY.split(trimmed);
        StringBuilder camel = new StringBuilder();
        StringBuilder lower = new StringBuilder();
        StringBuilder upper = new StringBuilder();
        boolean firstUpperWord = true;
        for (String word : words) {
            if (word.isEmpty()) continue;
            String wLower = word.toLowerCase(Locale.ROOT);
            camel.append(Character.toUpperCase(wLower.charAt(0)));
            if (wLower.length() > 1) camel.append(wLower.substring(1));
            lower.append(wLower);
            if (!firstUpperWord) upper.append('_');
            upper.append(word.toUpperCase(Locale.ROOT));
            firstUpperWord = false;
        }
        return new NameDeriver(lower.toString(), camel.toString(), upper.toString());
    }

    /** small caps form: ファイル名・言語 ID・設定キー prefix。 */
    public String lower() { return lower; }

    /** CamelCase form: Java クラス名 prefix・grammar { Name } の Name。 */
    public String className() { return className; }

    /** UPPER_SNAKE form: 定数用。 */
    public String upper() { return upper; }

    /** デフォルトパッケージ ("org.example." + lower)。 */
    public String defaultPackage() {
        return "org.example." + lower;
    }

    /**
     * パッケージ名から groupId を導出する。最後の要素を捨てる。
     * 1要素しかなければそのまま返す。
     */
    public static String groupIdFromPackage(String packageName) {
        int idx = packageName.lastIndexOf('.');
        if (idx <= 0) return packageName;
        return packageName.substring(0, idx);
    }

    /** Java パッケージ名をディレクトリパスに変換する ("a.b.c" → "a/b/c")。 */
    public static String packageToPath(String packageName) {
        return packageName.replace('.', '/');
    }

    /** ".foo" / "foo" を "." 付きの形に正規化する。 */
    public static String normalizeExtension(String ext) {
        if (ext == null || ext.isBlank()) {
            throw new IllegalArgumentException("extension must be non-blank");
        }
        String trimmed = ext.trim();
        return trimmed.startsWith(".") ? trimmed : "." + trimmed;
    }
}
