package org.unlaxer.dsl.codegen.rust;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Bounded compiler-front-end contract shared with the standalone Rust regex core. This validates
 * Rust support, never replaces Java Pattern for the Java target.
 */
public final class RegexCoreValidator {
    private RegexCoreValidator() {}

    public enum Kind {
        Syntax,
        Unsupported,
        ResourceLimit
    }

    public static final class Rejection extends IllegalArgumentException {
        private final Kind kind;
        private final int offset;

        private Rejection(Kind kind, int offset, String reason) {
            super("regex " + kind + " at CP " + offset + ": " + reason);
            this.kind = kind;
            this.offset = offset;
        }

        public Kind kind() {
            return kind;
        }

        public int offset() {
            return offset;
        }
    }

    public static void validate(String pattern) {
        new Compiler(pattern).compile();
    }

    private record Flags(boolean i, boolean d, boolean m, boolean s, boolean x) {
        Flags with(int flag, boolean on) {
            return new Flags(
                    flag == 'i' ? on : i,
                    flag == 'd' ? on : d,
                    flag == 'm' ? on : m,
                    flag == 's' ? on : s,
                    flag == 'x' ? on : x);
        }
    }

    // Instruction counts include matching continuations, not quantifier expansion.
    private record Atom(Set set, Integer scalar) {}

    private static final class Compiler {
        private final int[] text;
        private int at, nodes, setWork;
        private boolean quote;
        private Flags flags = new Flags(false, false, false, false, false);

        Compiler(String pattern) {
            if (pattern.codePointCount(0, pattern.length()) > 16_384)
                throw new Rejection(Kind.ResourceLimit, 16_384, "pattern size limit");
            text = pattern.codePoints().toArray();
            for (int i = 0; i < text.length; i++)
                if (text[i] >= 0xd800 && text[i] <= 0xdfff)
                    throw new Rejection(Kind.Unsupported, i, "unpaired UTF16 scalar");
        }

        private Rejection reject(Kind kind, String message) {
            return new Rejection(kind, at, message);
        }

        private Rejection syntax(String message) {
            return reject(Kind.Syntax, message);
        }

        private Rejection unsupported(String message) {
            return reject(Kind.Unsupported, message);
        }

        private void budget(int depth) {
            if (++nodes > 8_192 || depth > 64)
                throw reject(Kind.ResourceLimit, "pattern depth or expression size limit");
        }

        private void setBudget(Set a, Set b) {
            setWork += Math.max(1, a.ranges.size() + b.ranges.size());
            if (setWork > 1_000_000)
                throw reject(Kind.ResourceLimit, "character-class set operation limit");
        }

        private int raw() {
            return at < text.length ? text[at++] : -1;
        }

        private boolean pair(int a, int b) {
            return at + 1 < text.length && text[at] == a && text[at + 1] == b;
        }

        private void ignored() {
            while (true) {
                if (quote) {
                    if (pair('\\', 'E')) {
                        at += 2;
                        quote = false;
                        continue;
                    }
                    return;
                }
                if (pair('\\', 'Q')) {
                    at += 2;
                    quote = true;
                    continue;
                }
                if (!flags.x) return;
                if (at < text.length && space(text[at])) at++;
                else if (at < text.length && text[at] == '#') {
                    at++;
                    while (at < text.length && !terminator(text[at], flags.d)) at++;
                } else return;
            }
        }

        private int peek() {
            ignored();
            return at < text.length ? text[at] : -1;
        }

        private int next() {
            int cp = peek();
            if (cp != -1) at++;
            return cp;
        }

        private boolean eat(int cp) {
            if (peek() == cp && !quote) {
                at++;
                return true;
            }
            return false;
        }

        private int escapePeek() {
            if (flags.x) {
                while (at < text.length) {
                    if (space(text[at])) at++;
                    else if (text[at] == '#') {
                        at++;
                        while (at < text.length && !terminator(text[at], flags.d)) at++;
                    } else break;
                }
            }
            return at < text.length ? text[at] : -1;
        }

        private int escapeNext() {
            int cp = escapePeek();
            if (cp != -1) at++;
            return cp;
        }

        private boolean escapeEat(int cp) {
            if (escapePeek() == cp) {
                at++;
                return true;
            }
            return false;
        }

        private void require(int cp, String message) {
            if (!eat(cp)) throw syntax(message);
        }

        private void compile() {
            int instructions = alternatives(0) + 1;
            if (peek() != -1) throw syntax("unmatched closing parenthesis");
            if (instructions > 32_768)
                throw reject(Kind.ResourceLimit, "compiled instruction limit");
        }

        private int alternatives(int depth) {
            budget(depth);
            int count = sequence(depth);
            while (eat('|')) count += sequence(depth) + 1;
            return count;
        }

        private int sequence(int depth) {
            int count = 0;
            while (peek() != -1 && (quote || peek() != ')' && peek() != '|')) {
                Integer atom = atom(depth);
                if (atom != null) count += quantifier(atom, depth);
            }
            return count;
        }

        private Integer atom(int depth) {
            budget(depth);
            int cp = next();
            if (quote) return 1;
            return switch (cp) {
                case '(' -> group(depth + 1);
                case '[' -> {
                    characterClass(depth + 1);
                    yield 1;
                }
                case '\\' -> {
                    escape(false);
                    yield 1;
                }
                case '*', '+', '?', '{' -> throw syntax("dangling or malformed quantifier");
                default -> 1;
            };
        }

        private Integer group(int depth) {
            budget(depth);
            Flags old = flags;
            boolean atomic = false;
            if (eat('?')) {
                if (eat(':')) {
                    /* ordinary matching group */
                } else if (eat('>')) atomic = true;
                else if (!quote && (peek() == '=' || peek() == '!' || peek() == '<'))
                    throw unsupported("lookaround and named groups are not implemented");
                else {
                    boolean enabled = true, any = false, minus = false;
                    while (true) {
                        int cp = peek();
                        if (cp == -1) throw syntax("unclosed flags group");
                        if (!quote && cp == '-' && !minus) {
                            at++;
                            enabled = false;
                            minus = true;
                            continue;
                        }
                        if (!quote && (cp == 'u' || cp == 'U' || cp == 'c'))
                            throw unsupported(
                                    "Unicode or canonical-equivalence regex flags are not"
                                        + " implemented");
                        if (!quote && "idmsx".indexOf(cp) >= 0) {
                            flags = flags.with(cp, enabled);
                            at++;
                            any = true;
                        } else break;
                    }
                    if (eat(')')) return null;
                    if (!any && !minus) throw syntax("unknown group construct or inline flag");
                    require(':', "unknown inline flag or missing flag-group colon");
                }
            }
            int result = alternatives(depth);
            require(')', "unclosed group");
            flags = old;
            return result + (atomic ? 2 : 0);
        }

        private int quantifier(int body, int depth) {
            if (eat('*') || eat('+') || eat('?')) {
                /* unbounded/fixed counter */
            } else if (eat('{')) {
                int min = count(), max = min;
                if (escapeEat(',')) max = digit(escapePeek()) ? count() : Integer.MAX_VALUE;
                if (!escapeEat('}')) throw syntax("unclosed or malformed repetition");
                if (max < min) throw syntax("repetition maximum is below minimum");
            } else return body;
            budget(depth);
            boolean reluctant = eat('?');
            boolean possessive = !reluctant && eat('+');
            return body + 2 + (possessive ? 2 : 0);
        }

        private int count() {
            if (at == text.length || !digit(text[at])) throw syntax("missing repetition count");
            long value = 0;
            while (digit(escapePeek())) {
                value = value * 10 + text[at++] - '0';
                if (value > Integer.MAX_VALUE) throw syntax("repetition count overflow");
            }
            return (int) value;
        }

        private boolean intersection() {
            int saved = at;
            boolean savedQuote = quote;
            boolean result = eat('&') && eat('&');
            at = saved;
            quote = savedQuote;
            return result;
        }

        private Set characterClass(int depth) {
            budget(depth);
            boolean negated = eat('^'), first = true;
            Set result = null;
            while (true) {
                Set union = new Set();
                boolean any = false;
                while (true) {
                    int cp = peek();
                    if (cp == -1) throw syntax("unclosed character class");
                    if (!first && !quote && cp == ']') break;
                    if (!quote && cp == '&' && intersection()) break;
                    if (result != null && !any && !quote && cp == '&')
                        throw unsupported(
                                "degenerate character-class ampersand operand is not implemented");
                    first = false;
                    budget(depth);
                    Atom atom = classAtom(depth);
                    Set set = atom.set;
                    any = true;
                    if (atom.scalar != null && eat('-')) {
                        if (peek() == ']' && !quote) set = set.union(Set.single('-', flags.i));
                        else {
                            Atom end = classAtom(depth);
                            if (end.scalar == null) throw syntax("illegal character range");
                            if (atom.scalar > end.scalar) throw syntax("reversed character range");
                            set = Set.range(atom.scalar, end.scalar, flags.i);
                        }
                    }
                    setBudget(union, set);
                    union = union.union(set);
                }
                if (!any)
                    throw unsupported("degenerate character-class intersection is not implemented");
                if (result == null) result = union;
                else {
                    setBudget(result, union);
                    result = result.intersect(union);
                }
                if (eat(']')) break;
                require('&', "invalid character class operator");
                require('&', "invalid character class intersection");
            }
            if (negated) {
                setBudget(result, new Set());
                result = result.complement();
            }
            return result;
        }

        private Atom classAtom(int depth) {
            int cp = next();
            if (cp == -1) throw syntax("unclosed character class");
            if (!quote && cp == '[') return new Atom(characterClass(depth + 1), null);
            if (!quote && cp == '\\') return escape(true);
            return new Atom(Set.single(cp, flags.i), cp);
        }

        private Atom scalar(int cp) {
            return new Atom(Set.single(cp, flags.i), cp);
        }

        private int scalarValue(int cp) {
            if (cp >= 0xd800 && cp <= 0xdfff) throw unsupported("unpaired UTF16 surrogate");
            if (!Character.isValidCodePoint(cp)) throw syntax("invalid Unicode scalar escape");
            return cp;
        }

        private int hex(int width) {
            int value = 0;
            for (int i = 0; i < width; i++) {
                int cp = escapeNext(), digit = hexDigit(cp);
                if (digit < 0) throw syntax("invalid hexadecimal escape");
                value = value * 16 + digit;
            }
            return value;
        }

        private Atom escape(boolean inClass) {
            int cp = raw();
            if (cp == -1) throw syntax("trailing backslash");
            Set predefined = Set.ascii(cp);
            if (predefined != null) return new Atom(predefined, null);
            int value;
            switch (cp) {
                case 't':
                    value = '\t';
                    break;
                case 'n':
                    value = '\n';
                    break;
                case 'r':
                    value = '\r';
                    break;
                case 'f':
                    value = '\f';
                    break;
                case 'a':
                    value = 7;
                    break;
                case 'e':
                    value = 27;
                    break;
                case 'c':
                    {
                        int next = escapeNext();
                        if (next == -1) throw syntax("missing control character");
                        if (next > 127)
                            throw unsupported("non-ASCII control escape is not implemented");
                        value = next ^ 64;
                        break;
                    }
                case 'x':
                    {
                        if (escapeEat('{')) {
                            long v = 0;
                            int digits = 0;
                            while (escapePeek() != -1 && escapePeek() != '}') {
                                int d = hexDigit(escapeNext());
                                if (d < 0) throw syntax("invalid hexadecimal escape");
                                v = v * 16 + d;
                                digits++;
                                if (v > 0xffffffffL) throw syntax("hexadecimal escape overflow");
                            }
                            if (digits == 0) throw syntax("empty hexadecimal escape");
                            if (!escapeEat('}')) throw syntax("unclosed hexadecimal escape");
                            value = v > Integer.MAX_VALUE ? -1 : (int) v;
                        } else value = hex(2);
                        value = scalarValue(value);
                        break;
                    }
                case 'u':
                    {
                        value = hex(4);
                        if (value >= 0xd800 && value <= 0xdbff) {
                            if (!eat('\\') || !eat('u'))
                                throw unsupported("unpaired UTF16 surrogate");
                            int low = hex(4);
                            if (low < 0xdc00 || low > 0xdfff)
                                throw unsupported("unpaired UTF16 surrogate");
                            value = 0x10000 + ((value - 0xd800) << 10) + low - 0xdc00;
                        }
                        value = scalarValue(value);
                        break;
                    }
                case '0':
                    {
                        int first = escapeNext();
                        if (first < '0' || first > '7') throw syntax("invalid octal escape");
                        value = first - '0';
                        for (int i = 0; i < 2 && !(i == 1 && first > '3'); i++) {
                            int next = escapePeek();
                            if (next < '0' || next > '7') break;
                            at++;
                            value = value * 8 + next - '0';
                        }
                        break;
                    }
                case 'p':
                case 'P':
                    {
                        StringBuilder name = new StringBuilder();
                        if (escapeEat('{')) {
                            int next;
                            while ((next = escapeNext()) != -1 && next != '}')
                                name.appendCodePoint(next);
                            if (at == 0 || text[at - 1] != '}')
                                throw syntax("unclosed property escape");
                        } else {
                            int next = escapeNext();
                            if (next == -1) throw syntax("missing property name");
                            name.appendCodePoint(next);
                        }
                        if (name.isEmpty()) throw syntax("empty property name");
                        Set set = Set.posix(name.toString(), flags.i);
                        if (set == null) throw unsupported("Unicode property is not implemented");
                        return new Atom(cp == 'P' ? set.complement() : set, null);
                    }
                case 'A':
                case 'G':
                case 'z':
                case 'Z':
                    if (inClass) throw syntax("anchor in character class");
                    return new Atom(null, null);
                case 'b':
                case 'B':
                    if (inClass) throw syntax("word boundary in character class");
                    throw unsupported("Unicode word boundaries are not implemented");
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                case 'k':
                    throw unsupported("backreferences are not implemented");
                case 'R':
                case 'X':
                case 'h':
                case 'H':
                case 'v':
                case 'V':
                case 'N':
                    throw unsupported(
                            "Unicode line, space, grapheme or named matchers are not implemented");
                default:
                    if (cp < 128 && Character.isLetter(cp))
                        throw syntax("unknown alphabetic escape");
                    value = cp;
            }
            return scalar(value);
        }
    }

    private static boolean digit(int cp) {
        return cp >= '0' && cp <= '9';
    }

    private static int hexDigit(int cp) {
        return cp >= '0' && cp <= '9'
                ? cp - '0'
                : cp >= 'a' && cp <= 'f'
                        ? cp - 'a' + 10
                        : cp >= 'A' && cp <= 'F' ? cp - 'A' + 10 : -1;
    }

    private static boolean terminator(int cp, boolean unix) {
        return cp == '\n' || !unix && (cp == '\r' || cp == 0x85 || cp == 0x2028 || cp == 0x2029);
    }

    private static boolean space(int cp) {
        return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || cp == 11 || cp == 12;
    }

    private static final class Set {
        final List<int[]> ranges;

        Set() {
            ranges = new ArrayList<>();
        }

        Set(List<int[]> ranges) {
            this.ranges = ranges;
        }

        static Set range(int lo, int hi, boolean insensitive) {
            var ranges = new ArrayList<int[]>();
            if (lo <= Math.min(hi, 0xd7ff)) ranges.add(new int[] {lo, Math.min(hi, 0xd7ff)});
            if (Math.max(lo, 0xe000) <= hi) ranges.add(new int[] {Math.max(lo, 0xe000), hi});
            if (insensitive)
                for (int c = 'A'; c <= 'Z'; c++) {
                    if (lo <= c && c <= hi) ranges.add(new int[] {c + 32, c + 32});
                    if (lo <= c + 32 && c + 32 <= hi) ranges.add(new int[] {c, c});
                }
            return normalize(ranges);
        }

        static Set single(int cp, boolean insensitive) {
            return range(cp, cp, insensitive);
        }

        static Set normalize(List<int[]> values) {
            values.sort(Comparator.comparingInt(a -> a[0]));
            var result = new ArrayList<int[]>();
            for (int[] value : values) {
                if (!result.isEmpty() && value[0] <= result.get(result.size() - 1)[1] + 1) {
                    int[] last = result.get(result.size() - 1);
                    last[1] = Math.max(last[1], value[1]);
                } else result.add(value.clone());
            }
            return new Set(result);
        }

        Set union(Set other) {
            var all = new ArrayList<int[]>(ranges);
            all.addAll(other.ranges);
            return normalize(all);
        }

        Set intersect(Set other) {
            var result = new ArrayList<int[]>();
            int i = 0, j = 0;
            while (i < ranges.size() && j < other.ranges.size()) {
                int[] a = ranges.get(i), b = other.ranges.get(j);
                int lo = Math.max(a[0], b[0]), hi = Math.min(a[1], b[1]);
                if (lo <= hi) result.add(new int[] {lo, hi});
                if (a[1] < b[1]) i++;
                else j++;
            }
            return new Set(result);
        }

        Set complement() {
            var values = new ArrayList<int[]>();
            int start = 0;
            for (int[] range : ranges) {
                if (start < range[0]) values.add(new int[] {start, range[0] - 1});
                start = range[1] + 1;
            }
            if (start <= 0x10ffff) values.add(new int[] {start, 0x10ffff});
            return new Set(values).intersect(range(0, 0x10ffff, false));
        }

        static Set ascii(int cp) {
            Set result =
                    switch (cp) {
                        case 'd', 'D' -> range('0', '9', false);
                        case 'w', 'W' ->
                                range('a', 'z', false)
                                        .union(range('A', 'Z', false))
                                        .union(range('0', '9', false))
                                        .union(single('_', false));
                        case 's', 'S' -> range(9, 13, false).union(single(' ', false));
                        default -> null;
                    };
            return result != null && Character.isUpperCase(cp) ? result.complement() : result;
        }

        static Set posix(String name, boolean insensitive) {
            Set result =
                    switch (name) {
                        case "Lower" -> range('a', 'z', insensitive);
                        case "Upper" -> range('A', 'Z', insensitive);
                        case "ASCII" -> range(0, 127, false);
                        case "Alpha" -> range('a', 'z', true);
                        case "Digit" -> range('0', '9', false);
                        case "Alnum" -> range('a', 'z', true).union(range('0', '9', false));
                        case "Punct" ->
                                range('!', '/', false)
                                        .union(range(':', '@', false))
                                        .union(range('[', '`', false))
                                        .union(range('{', '~', false));
                        case "Graph" -> range('!', '~', false);
                        case "Print" -> range(' ', '~', false);
                        case "Blank" -> single(' ', false).union(single('\t', false));
                        case "Cntrl" -> range(0, 31, false).union(single(127, false));
                        case "XDigit" -> range('0', '9', false).union(range('a', 'f', true));
                        case "Space" -> ascii('s');
                        default -> null;
                    };
            return result;
        }
    }
}
