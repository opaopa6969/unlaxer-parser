package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * UBNF bootstrap が非BMP文字（サロゲートペア）を含むソースで offset を取り違えないこと。
 *
 * <p>Rust 側の対は {@code rust/unlaxer-ubnf/tests/frontend.rs} の
 * {@code spans_are_utf8_and_codepoint_ranges_and_diagnostics_use_crlf_lines}
 * （同じ 😀 入りソースで byte / code point / 行・列を別々に検査している）。
 * ubnfc の 4 実装クロスチェックは、手書きの旧 UBNF 拡張で UTF-16 と code point の
 * 取り違えを見つけている。ここは Java 側の「取り違えていない」を杭にする。
 *
 * <p>受理側（😀 を含むトークン宣言の構文木）は仕様由来オラクル
 * {@code spec-corpus/ubnf/token-declarations.json} の {@code token/negation-codepoint-set}
 * が Java / Rust の両方で押さえているので、ここでは拒否側の位置報告を見る。
 */
public class UbnfNonBmpOffsetTest {

    @Test
    public void terminalsAndCommentsKeepEverySurrogatePair() {
        String source = "grammar G {\n  // 😀 コメント\n  Root ::= '😀🙏' ;\n}";
        UBNFAST.UBNFFile file = UBNFMapper.parse(source);
        UBNFAST.RuleDecl rule = file.grammars().get(0).rules().get(0);
        UBNFAST.ChoiceBody body = (UBNFAST.ChoiceBody) rule.body();
        UBNFAST.AnnotatedElement element = body.alternatives().get(0).elements().get(0);
        UBNFAST.TerminalElement terminal = (UBNFAST.TerminalElement) element.element();
        assertEquals("😀🙏", terminal.value());
        assertEquals("code point では 2 個", 2,
            terminal.value().codePointCount(0, terminal.value().length()));
        assertEquals("UTF-16 では 4 単位", 4, terminal.value().length());
    }

    @Test
    public void trailingInputAfterNonBmpIsReportedAtTheUtf16Column() {
        // Rust 側 frontend.rs と同じ形の入力。'!' は 😀 の後ろにある。
        String source = "grammar G { Root ::= '😀'; } !";
        int utf16Index = source.indexOf('!');
        int codePointIndex = source.codePointCount(0, utf16Index);
        assertEquals("サロゲートペア 1 個ぶん UTF-16 の方が 1 大きい", 1, utf16Index - codePointIndex);

        IllegalArgumentException error =
            assertThrows(IllegalArgumentException.class, () -> UBNFMapper.parse(source));
        String message = error.getMessage();
        // 行・列は 1 始まりの UTF-16 インデックス基準（UBNFMapper#positionOf の契約）。
        assertTrue("実測: " + message, message.contains("line 1, column " + (utf16Index + 1)));
        assertTrue("未消費の入力そのものを見せる。実測: " + message, message.endsWith("!"));
    }

    @Test
    public void multilineSourceCountsLinesAcrossNonBmpCharacters() {
        String source = "grammar G {\n  // 😀\n  Root ::= 'a';\n}\n😀";
        int utf16Index = source.lastIndexOf('😀');
        IllegalArgumentException error =
            assertThrows(IllegalArgumentException.class, () -> UBNFMapper.parse(source));
        String message = error.getMessage();
        // 5 行目の 1 桁目。1 行目の 😀 を 2 文字と数えても行数は変わらない。
        assertTrue("実測: " + message, message.contains("line 5, column 1"));
        assertEquals("最後の 😀 は 5 行目の先頭", '\n', source.charAt(utf16Index - 1));
    }
}
