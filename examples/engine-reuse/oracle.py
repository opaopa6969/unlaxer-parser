"""Independent oracle for the written Packet language, not output from any engine.

Positions use Python Unicode scalar indexing. Captured terminal strings follow the
mapper String.strip / Character.isWhitespace contract (see docs/tiny-code-fence.md),
not scope-capture String.trim or Python's broader Unicode strip.
"""
class Rejected(Exception):
    def __init__(self, position, expected): self.position, self.expected = position, expected

def oracle(source, with_expected=False):
    cursor = 0
    def literal(value):
        nonlocal cursor
        if not source.startswith(value, cursor): raise Rejected(cursor, value)
        cursor += len(value)
    def span(start, end):
        a = len(source[:start].encode('utf-16-le')) // 2
        b = len(source[:end].encode('utf-16-le')) // 2
        return f'cp={start}:{end},utf16={a}:{b},text={source[start:end].encode().hex()}'
    def atom():
        nonlocal cursor
        start = cursor
        literal('<')
        values = []
        while cursor < len(source) and source[cursor] != '>':
            char = source[cursor]
            java_space = ord(char) in {*range(9,14), *range(28,33), 0x1680, *range(0x2000,0x2007), *range(0x2008,0x200B), 0x2028,0x2029,0x205F,0x3000}
            values.append('s' + ('' if java_space else char).encode().hex())
            cursor += 1
        literal('>')
        return f'Atom({span(start,cursor)},value=[{",".join(values)}])'
    try:
        literal('p')
        first, second = atom(), atom()
        literal('[')
        others = []
        while cursor < len(source) and source[cursor] == '<': others.append(atom())
        literal(']'); literal('!')
        if cursor != len(source): raise Rejected(cursor, "end of input")
        result = f'ACCEPT\tPacket({span(0,cursor)},first={first},second={second},others=[{",".join(others)}])'
        return (result, None) if with_expected else result
    except Rejected as e:
        result = f'REJECT\t{e.position}'
        return (result, e.expected) if with_expected else result
