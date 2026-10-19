#!/usr/bin/env python3
"""Writes spec-corpus/call-by-value/cases.json (usage: python3 -I generate_cases.py cases.json).

Expected values are written by hand. Each accepted grammar has a hand-written equivalent that
spells the expansion with CAPTURE_SCOPE / CAPTURE / SAME_AS. A generated capture name such as
`Body(=tag)` cannot be written in UBNF, so the equivalent uses an identifier and `captures` maps
the generated name to it. Instance names are computed here from hand-written canonical keys
with an independent FNV-1a-64 implementation; error spans are located by substring search in
the grammar text and limit positions follow the documented accounting by hand. Nothing is read
back from the implementations.
"""
import json, sys

def fnv(key):
    h = 0xcbf29ce484222325
    for b in key.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return "%016x" % h

def inst(key):
    return key[: key.index("(")] + "_" + fnv(key)

def t(text):
    return "t(" + ".".join("%x" % ord(c) for c in text) + ")"

def r(name):
    return "r(" + name + ")"

def y(name):
    return "y(" + name + ")"

def o(body):
    return "o(" + body + ")"

def line_col(source, cp):
    line, col, cr = 1, 1, False
    for c in source[:cp]:
        if c == "\r":
            line += 1; col = 1
        elif c == "\n" and cr:
            pass
        elif c == "\n":
            line += 1; col = 1
        else:
            col += 1
        cr = c == "\r"
    return line, col

def at(source, needle, occurrence=0, length=None):
    start = -1
    for _ in range(occurrence + 1):
        start = source.index(needle, start + 1)
    end = start + (len(needle) if length is None else length)
    return start, end

def node(type_, span, **fields):
    return {"type": type_, "span": list(span), "fields": fields}

def ok(value, length):
    """Accepted input: the AST, and the context consumed / matched the whole input."""
    return {"expected": {"status": "ok", "value": value},
            "state": {"success": True, "consumed": length, "matched": length}}

def syntax(offset):
    """Rejected input: the farthest failure, and the context is back at its start."""
    return {"expected": {"status": "syntax", "offset": offset},
            "state": {"success": False, "consumed": 0, "matched": 0}}

def case(text, outcome):
    row = {"input": text}
    row.update(outcome)
    return row

HEAD = "grammar Cbv {\n  @package: cbvprobe\n  @whitespace: none\n"
cases = []

def fill(template, keys):
    for n, k in enumerate(keys):
        template = template.replace("{i%d}" % n, inst(k))
    return template

def accept(name, description, grammar, keys, captures, expanded, spans, inputs):
    names = {k: inst(k) for k in keys}
    exp = fill(expanded, keys)
    for generated, written in captures.items():
        assert generated not in exp and written in exp, (name, generated, written)
    cases.append({
        "name": name,
        "description": description,
        "grammar": grammar,
        "status": "accept",
        "instances": [{"key": k, "name": names[k]} for k in keys],
        "captures": captures,
        "expanded": exp,
        "spans": [{"rule": names[keys[n]], "span": list(at(grammar, s))} for n, s in spans],
        "inputs": inputs,
    })

# 1. Named closing tags: recursion through an ordinary rule, nested instances of one rule.
g1 = HEAD + """  @root @mapping(Root, params=[items])
  Root ::= Elem @items { Elem @items } ;
  Elem ::= '<' Body(Name) ;
  Name ::= Letter { Letter } ;
  Letter ::= 'a' | 'b' | '😀' ;
  Text ::= 'x' { 'x' } ;
  Body(=tag) ::= '>' { Elem | Text } '</' tag '>' ;
}
"""
k1 = ["Body(" + r("Name") + ")"]
e1 = HEAD + """  @root @mapping(Root, params=[items])
  Root ::= Elem @items { Elem @items } ;
  Elem ::= '<' {i0} ;
  Name ::= Letter { Letter } ;
  Letter ::= 'a' | 'b' | '😀' ;
  Text ::= 'x' { 'x' } ;
  {i0} ::= CAPTURE_SCOPE(CAPTURE(Body_tag, Name) '>' { Elem | Text } '</' SAME_AS(Body_tag) '>') ;
}
"""
accept("named-closing-tag", "the closing tag repeats the opening name; nested elements restore the outer binding",
    g1, k1, {"Body(=tag)": "Body_tag"}, e1, [(0, "Body(=tag) ::= '>' { Elem | Text } '</' tag '>' ;")],
    [
        case("<a></a>", ok(node("Root", (0, 7), items=["<a></a>"]), 7)),
        case("<a><b>x</b></a>", ok(node("Root", (0, 15), items=["<a><b>x</b></a>"]), 15)),
        case("<ab><a></a>xx</ab>", ok(node("Root", (0, 18), items=["<ab><a></a>xx</ab>"]), 18)),
        case("<a><a></a></a><😀></😀>", ok(node("Root", (0, 21), items=["<a><a></a></a>", "<😀></😀>"]), 21)),
        # The inner SAME_AS(b) fails at 8 against 'a'.
        case("<a><b></a></b>", syntax(8)),
        # SAME_AS(ab) needs two code points at 6 and sees "a>".
        case("<ab></a>", syntax(6)),
        # SAME_AS(a) matches at 5; '>' fails at 6 against 'b'.
        case("<a></ab>", syntax(6)),
    ])

# 2. Heredoc. Rule bodies have no negative lookahead, so body lines are indented and the
# unindented closing line must repeat the tag.
g2 = HEAD + """  @root @mapping(Root, params=[docs])
  Root ::= Doc @docs { NL Doc @docs } ;
  Doc ::= '<<' Here(Tag) ;
  Tag ::= Upper { Upper } ;
  Upper ::= 'E' | 'O' | 'T' ;
  NL ::= '\\r\\n' | '\\n' ;
  Line ::= ' ' { Char } NL ;
  Char ::= 'a' | 'b' | ' ' | 'E' | 'O' | 'T' | '😀' ;
  Here(=tag) ::= NL { Line } tag ;
}
"""
k2 = ["Here(" + r("Tag") + ")"]
e2 = HEAD + """  @root @mapping(Root, params=[docs])
  Root ::= Doc @docs { NL Doc @docs } ;
  Doc ::= '<<' {i0} ;
  Tag ::= Upper { Upper } ;
  Upper ::= 'E' | 'O' | 'T' ;
  NL ::= '\\r\\n' | '\\n' ;
  Line ::= ' ' { Char } NL ;
  Char ::= 'a' | 'b' | ' ' | 'E' | 'O' | 'T' | '😀' ;
  {i0} ::= CAPTURE_SCOPE(CAPTURE(Here_tag, Tag) NL { Line } SAME_AS(Here_tag)) ;
}
"""
accept("heredoc", "the closing line repeats the opening tag; indented lines may contain the tag",
    g2, k2, {"Here(=tag)": "Here_tag"}, e2, [(0, "Here(=tag) ::= NL { Line } tag ;")],
    [
        case("<<EOT\n ab\n EOT\nEOT", ok(node("Root", (0, 18), docs=["<<EOT\n ab\n EOT\nEOT"]), 18)),
        case("<<EO\r\n 😀\r\nEO\n<<T\nT", ok(node("Root", (0, 18), docs=["<<EO\r\n 😀\r\nEO", "<<T\nT"]), 18)),
        case("<<EOT\nEOT", ok(node("Root", (0, 9), docs=["<<EOT\nEOT"]), 9)),
        # At 9 neither an indented line nor the full tag EOT (only "EO" remains).
        case("<<EOT\n a\nEO", syntax(9)),
        case("<<EOT\n a\nTOE", syntax(9)),
    ])

# 3. Value arguments are matched left to right before the body; by-name arguments are not.
g3 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= Swap('a' 'b', 'c' | 'd', '-') @text ;
  Swap(=x, =y, sep) ::= sep y Wrap(x) ;
  Wrap(z) ::= '[' z ']' ;
}
"""
k3 = ["Swap(" + t("a") + " " + t("b") + "," + t("c") + "|" + t("d") + "," + t("-") + ")",
      "Wrap(" + y("Swap(=x)") + ")"]
e3 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= {i0} @text ;
  {i0} ::= CAPTURE_SCOPE(CAPTURE(Swap_x, ( 'a' 'b' )) CAPTURE(Swap_y, ( 'c' | 'd' )) '-' SAME_AS(Swap_y) {i1}) ;
  {i1} ::= '[' SAME_AS(Swap_x) ']' ;
}
"""
accept("sequential-values", "two value parameters in order, a by-name one, and a value passed by name",
    g3, k3, {"Swap(=x)": "Swap_x", "Swap(=y)": "Swap_y"}, e3,
    [(0, "Swap(=x, =y, sep) ::= sep y Wrap(x) ;"), (1, "Wrap(z) ::= '[' z ']' ;")],
    [
        case("abc-c[ab]", ok(node("Root", (0, 9), text="abc-c[ab]"), 9)),
        case("abd-d[ab]", ok(node("Root", (0, 9), text="abd-d[ab]"), 9)),
        case("abc-d[ab]", syntax(4)),
        case("abc-c[ba]", syntax(6)),
        case("ab-c[ab]", syntax(2)),
    ])

# 4. A value passed on as a value argument, and nested instances of one rule.
g4 = HEAD + """  @root @mapping(Root, params=[first, second])
  Root ::= Twice('a') @first ':' Pair(Pair('b')) @second ;
  Twice(=v) ::= Again(v) ;
  Again(=w) ::= w ;
  Pair(=p) ::= p ;
}
"""
pair_b = inst("Pair(" + t("b") + ")")
k4 = ["Twice(" + t("a") + ")", "Again(" + y("Twice(=v)") + ")", "Pair(" + t("b") + ")", "Pair(" + r(pair_b) + ")"]
e4 = HEAD + """  @root @mapping(Root, params=[first, second])
  Root ::= {i0} @first ':' {i3} @second ;
  {i0} ::= CAPTURE_SCOPE(CAPTURE(Twice_v, 'a') {i1}) ;
  {i1} ::= CAPTURE_SCOPE(CAPTURE(Again_w, SAME_AS(Twice_v)) SAME_AS(Again_w)) ;
  {i2} ::= CAPTURE_SCOPE(CAPTURE(Pair_p, 'b') SAME_AS(Pair_p)) ;
  {i3} ::= CAPTURE_SCOPE(CAPTURE(Pair_p, {i2}) SAME_AS(Pair_p)) ;
}
"""
accept("forwarded-and-nested-values", "Again(v) matches the value once more; Pair(Pair('b')) shadows and restores Pair(=p)",
    g4, k4, {"Twice(=v)": "Twice_v", "Again(=w)": "Again_w", "Pair(=p)": "Pair_p"}, e4,
    [(0, "Twice(=v) ::= Again(v) ;"), (1, "Again(=w) ::= w ;"), (3, "Pair(=p) ::= p ;")],
    [
        case("aaa:bbbb", ok(node("Root", (0, 8), first="aaa", second="bbbb"), 8)),
        case("aa:bbbb", syntax(2)),
        # The outer Pair binds "bb"; replaying it needs two code points at 6.
        case("aaa:bbb", syntax(6)),
    ])

# 5. An empty value replays with zero width; an unused value parameter still consumes.
g5 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= Opt([ 'x' ]) @text 'y' Skip('z') ;
  Opt(=o) ::= ':' o ;
  Skip(=s) ::= '!' ;
}
"""
k5 = ["Opt(" + o(t("x")) + ")", "Skip(" + t("z") + ")"]
e5 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= {i0} @text 'y' {i1} ;
  {i0} ::= CAPTURE_SCOPE(CAPTURE(Opt_o, [ 'x' ]) ':' SAME_AS(Opt_o)) ;
  {i1} ::= CAPTURE_SCOPE(CAPTURE(Skip_s, 'z') '!') ;
}
"""
accept("empty-and-unused-values", "an empty value and a value parameter that the body never uses",
    g5, k5, {"Opt(=o)": "Opt_o", "Skip(=s)": "Skip_s"}, e5,
    [(0, "Opt(=o) ::= ':' o ;"), (1, "Skip(=s) ::= '!' ;")],
    [
        case("x:xyz!", ok(node("Root", (0, 6), text="x:x"), 6)),
        case(":yz!", ok(node("Root", (0, 4), text=":"), 4)),
        case("x:yz!", syntax(2)),
        case("x:xy!", syntax(4)),
    ])

# 6. A shared instance in failed alternatives, and a choice body grouped after the capture.
g6 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= Mark('a') @text 'x' | Mark('a') @text 'y' | Either('b') @text ;
  Mark(=m) ::= m ;
  Either(=v) ::= v '!' | '?' v ;
}
"""
k6 = ["Mark(" + t("a") + ")", "Either(" + t("b") + ")"]
e6 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= {i0} @text 'x' | {i0} @text 'y' | {i1} @text ;
  {i0} ::= CAPTURE_SCOPE(CAPTURE(Mark_m, 'a') SAME_AS(Mark_m)) ;
  {i1} ::= CAPTURE_SCOPE(CAPTURE(Either_v, 'b') ( SAME_AS(Either_v) '!' | '?' SAME_AS(Either_v) )) ;
}
"""
accept("alternatives-and-choice-body", "retrying a shared instance after a failed alternative; a choice body",
    g6, k6, {"Mark(=m)": "Mark_m", "Either(=v)": "Either_v"}, e6,
    [(0, "Mark(=m) ::= m ;"), (1, "Either(=v) ::= v '!' | '?' v ;")],
    [
        case("aax", ok(node("Root", (0, 3), text="aa"), 3)),
        case("aay", ok(node("Root", (0, 3), text="aa"), 3)),
        case("bb!", ok(node("Root", (0, 3), text="bb!"), 3)),
        case("b?b", ok(node("Root", (0, 3), text="b?b"), 3)),
        case("b?a", syntax(2)),
        case("aaz", syntax(2)),
    ])

# 7. A user capture with the parameter's name is a different binding; a by-name argument
# replays the user's binding inside the instance.
g7 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= CAPTURE(tag, 'q' | 'r') Body('a' | 'b', SAME_AS(tag)) @text SAME_AS(tag) ;
  Body(=tag, k) ::= k tag ;
}
"""
k7 = ["Body(" + t("a") + "|" + t("b") + "," + y("tag") + ")"]
e7 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= CAPTURE(tag, 'q' | 'r') {i0} @text SAME_AS(tag) ;
  {i0} ::= CAPTURE_SCOPE(CAPTURE(Body_tag, ( 'a' | 'b' )) SAME_AS(tag) SAME_AS(Body_tag)) ;
}
"""
accept("user-captures-beside-values", "user capture tag and value parameter tag are separate bindings",
    g7, k7, {"Body(=tag)": "Body_tag"}, e7, [(0, "Body(=tag, k) ::= k tag ;")],
    [
        case("qaqaq", ok(node("Root", (0, 5), text="aqa"), 5)),
        case("rbrbr", ok(node("Root", (0, 5), text="brb"), 5)),
        case("qaqbq", syntax(3)),
        case("qaqaa", syntax(4)),
    ])

LIMIT, NESTING = 65536, 128
# 8. Inline nesting of a value instance: Root's body (1), the instance body (1), the
# CAPTURE_SCOPE body (1) and the body's own groups; one more group is refused (below).
GROUPS = NESTING - 3
g8 = HEAD + "  @root @mapping(Root, params=[text])\n  Root ::= F('a') @text ;\n  F(=x) ::= " + "( " * GROUPS + "x" + " )" * GROUPS + " ;\n}\n"
k8 = ["F(" + t("a") + ")"]
e8 = HEAD + "  @root @mapping(Root, params=[text])\n  Root ::= {i0} @text ;\n  {i0} ::= CAPTURE_SCOPE(CAPTURE(F_x, 'a') " + "( " * GROUPS + "SAME_AS(F_x)" + " )" * GROUPS + ") ;\n}\n"
accept("value-nesting-at-limit", "the CAPTURE_SCOPE of a value instance counts toward the inline nesting of 128",
    g8, k8, {"F(=x)": "F_x"}, e8, [],
    [
        case("aa", ok(node("Root", (0, 2), text="aa"), 2)),
        case("ab", syntax(1)),
    ])

# Positioned frontend rejections.
def reject(name, grammar, code, needle, message, occurrence=0, length=None):
    start, end = at(grammar, needle, occurrence, length)
    line, column = line_col(grammar, start)
    cases.append({"name": name, "grammar": grammar, "status": "reject",
        "error": {"stage": "frontend", "code": code, "start": start, "end": end, "line": line, "column": column,
                  "message": code + ": " + message}})

def defined(grammar, needle):
    line, column = line_col(grammar, grammar.index(needle))
    return f"defined at line {line}, column {column}"

g = "grammar Bad { Root ::= F('a', 'b'); F(=x) ::= x; }"
reject("value-arity", g, "E-PARAM-ARITY", "F('a', 'b')", "rule F expects 1 argument(s), got 2; " + defined(g, "F(=x) ::="))
g = "grammar Bad { Root ::= F('a', 'b'); F(=x, x) ::= x; }"
reject("value-duplicate-parameter", g, "E-PARAM-DUPLICATE", "F(=x, x) ::= x;", "parameter x is declared more than once in rule F")
g = "grammar Bad { Root ::= F('a'); F(=Root) ::= Root; }"
reject("value-parameter-shadows-rule", g, "E-PARAM-SHADOW", "F(=Root) ::= Root;", "parameter Root of rule F has the same name as a rule or token")
g = "grammar Bad { Root ::= F('a'); F(=x) ::= 'a' F(x) | x; }"
reject("value-cycle", g, "E-PARAM-CYCLE", "F(x)", "parameterized call cycle F -> F; expansion would not terminate")
g = "grammar Bad { @root @mapping(Node, params=[t]) Root ::= F('a' @t); F(=x) ::= x; }"
reject("value-capture-in-argument", g, "E-PARAM-CAPTURE", "F('a' @t)", "captures and @typeof are not allowed in parameterized rule bodies or call arguments")
g = "grammar Bad { @root F(=x) ::= x; }"
reject("value-annotation", g, "E-PARAM-ANNOTATION", "@root F(=x) ::= x;", "parameterized rule F cannot have annotations; only transparent rules take parameters")
g = "grammar Bad { Root ::= F('a'); F(=x) ::= y; }"
reject("value-undefined", g, "E-PARAM-UNDEFINED", "y;", "rule F references undefined parameter, rule or token 'y'", length=1)
g = "grammar Bad { Root ::= 'a'; SAME_AS(=x) ::= x; }"
reject("value-reserved-name", g, "E-PARAM-RESERVED", "SAME_AS(=x) ::= x;", "parameterized rule name SAME_AS is reserved by a builtin call")
# A rule with value parameters wraps its whole instance in CAPTURE_SCOPE, which restores
# every binding on exit, so a user CAPTURE there would silently vanish: refuse it at the
# CAPTURE, in the body, in any argument of a call to that rule, and in nested arguments.
def scoped(rule, name):
    return ("CAPTURE(%s, ...) cannot appear in the body or call arguments of rule %s, "
            "whose value parameters restore every capture when it returns") % (name, rule)
g = "grammar Bad { Root ::= F('a'); F(=x) ::= CAPTURE(u, 'q') x; }"
reject("value-capture-in-body", g, "E-PARAM-CAPTURE", "CAPTURE(u, 'q')", scoped("F", "u"))
g = "grammar Bad { Root ::= F('a', CAPTURE(u, 'b')) SAME_AS(u); F(=x, y) ::= x y; }"
reject("value-capture-in-by-name-argument", g, "E-PARAM-CAPTURE", "CAPTURE(u, 'b')", scoped("F", "u"))
g = "grammar Bad { Root ::= F(CAPTURE(u, 'a')) SAME_AS(u); F(=x) ::= x; }"
reject("value-capture-in-value-argument", g, "E-PARAM-CAPTURE", "CAPTURE(u, 'a')", scoped("F", "u"))
g = "grammar Bad { Root ::= F('a'); F(=x) ::= G(CAPTURE(u, 'q')) x; G(y) ::= y; }"
reject("value-capture-in-nested-argument", g, "E-PARAM-CAPTURE", "CAPTURE(u, 'q')", scoped("F", "u"))
g = "grammar Bad { Root ::= G(F(CAPTURE(u, 'a'))); F(=x) ::= x; G(y) ::= y; }"
reject("value-capture-in-argument-of-argument", g, "E-PARAM-CAPTURE", "CAPTURE(u, 'a')", scoped("F", "u"))
# SAME_AS(x) would read a user binding x, never the value parameter x.
g = "grammar Bad { Root ::= F('a'); F(=x) ::= SAME_AS(x) x; }"
reject("value-same-as-parameter", g, "E-PARAM-SHADOW", "SAME_AS(x)",
       "SAME_AS(x) in rule F names value parameter x; write x to replay its value")
# A written reference that only an expansion name satisfies (it would read F(=p) from outside F).
leak = inst("H(" + y("F(=p)") + ")")
g = "grammar Bad { Root ::= F('a', F('b', " + leak + ")); F(=p, y) ::= y p H(p); H(y) ::= y; }"
reject("value-instance-name-reference", g, "E-PARAM-UNDEFINED", leak,
       "rule Root references '" + leak + "', which only names a rule created by parameterized expansion")
by_name = inst("F(" + t("a") + ")")
g = "grammar Bad { Root ::= F('a') " + by_name + "; F(x) ::= x; }"
reject("instance-name-reference", g, "E-PARAM-UNDEFINED", by_name,
       "rule Root references '" + by_name + "', which only names a rule created by parameterized expansion")
collision = inst("F(" + t("a") + ")")
g = "grammar Bad { Root ::= F('a'); F(=x) ::= x; " + collision + " ::= 'b'; }"
reject("value-name-collision", g, "E-PARAM-NAME-COLLISION", "F('a')", "expanded rule name " + collision + " collides with another rule, token or expansion")
# One group more than value-nesting-at-limit.
g = "grammar Bad { Root ::= F('a'); F(=x) ::= " + "( " * (GROUPS + 1) + "x" + " )" * (GROUPS + 1) + "; }"
reject("limit-value-body-nesting", g, "E-PARAM-LIMIT", "F('a')", "expansion of rule F nests 129 levels; the limit is 128")
# The argument sits inside CAPTURE inside CAPTURE_SCOPE: Root (1) + instance body (1) +
# scope body (1) + capture body (1) + its 125 groups = 129, while the call itself measures
# only Root (1) + the argument body (1) + 125 = 127.
k = NESTING - 3
arg = "(" * k + "'a'" + ")" * k
g = "grammar Bad { Root ::= F(" + arg + "); F(=x) ::= 'b'; }"
reject("limit-value-argument-nesting", g, "E-PARAM-LIMIT", "F(" + arg + ")", "expansion of rule F nests 129 levels; the limit is 128")
# Elements: the call charges its S-element argument, then the instance charges
# CAPTURE_SCOPE (1) + CAPTURE (1) + the grouped argument (S + 1) + 'b' (1): 2S + 4 > 65536.
S = (LIMIT - 4) // 2 + 1
g = "grammar Bad { Root ::= F(" + " ".join(["'a'"] * S) + "); F(=x) ::= 'b'; }"
reject("limit-value-elements", g, "E-PARAM-LIMIT", "F('a'", "expanded parameterized rules exceed 65536 elements",
       length=len("F(" + " ".join(["'a'"] * S) + ")"))

json.dump(cases, open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False, indent=2)
open(sys.argv[1], "a").write("\n")
print(len(cases), "cases")
