#!/usr/bin/env python3
"""Author a fixed-seed edit history without invoking any parser/backend."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEED = 383384
TEMPLATES = {
    'java': 'class C { String s = "日😀"; int f0(){ return 1; } }',
    'typescript': 'function f0():number { let s:string = "日😀"; return 1; }',
    'rust': 'fn f0()->i32 { let s = "日😀"; 1 }',
}
ENTRIES = {'java': 'CompilationUnit', 'typescript': 'SourceFile', 'rust': 'Crate'}
UNSUPPORTED = {
    'java': 'COMPLETION,HOVER,RENAME,FORMAT,CODE_ACTION',
    'typescript': 'RENAME,FORMAT,CODE_ACTION',
    'rust': 'COMPLETION,HOVER,DEFINITION,RENAME,FORMAT,CODE_ACTION',
}


def hex_text(text):
    return text.encode('utf-8').hex() or '-'


def author():
    rows = ['# language\tstep\tentry\tstart_cp\tend_cp\treplacement_utf8\texpected_utf8\taccepted\tcp_length\tutf16_length\teof_line\teof_character\trejected_history\tquery_state\tkind\tunsupported_operations']
    for language, template in TEMPLATES.items():
        source, number, word = '', '1', '日😀'
        state = SEED
        for step in range(1, 49):
            cycle, phase = divmod(step - 1, 8)
            state = (1664525 * state + 1013904223) & 0xffffffff
            if phase == 0:
                if cycle == 0:
                    start, end, replacement = 0, 0, template
                else:
                    old = 'f' + str(cycle - 1)
                    start = source.index(old)
                    end, replacement = start + len(old), 'f' + str(cycle)
                kind = 'insert-document' if cycle == 0 else 'rename-function'
            elif phase == 1:
                start = source.index('{') + 1
                end, replacement, kind = start, '\r\n', 'insert-crlf'
            elif phase == 2:
                start = source.rindex('}')
                end, replacement, kind = start + 1, '', 'remove-close'
            elif phase == 3:
                start = source.index(word)
                end = start + len(word)
                word = ('名🦀' if cycle % 2 == 0 else '日😀')
                replacement, kind = word, 'replace-unicode'
            elif phase == 4:
                start = end = len(source)
                replacement, kind = '}', 'restore-close'
            elif phase == 5:
                start = source.index('return ' + number) + len('return ') if language != 'rust' else source.rindex('; ' + number) + 2
                end = start + len(number)
                number = str(2 + state % 997)
                replacement, kind = number, 'replace-number'
            elif phase == 6:
                start = source.index('\r\n')
                end, replacement, kind = start + 2, '\n', 'crlf-to-lf'
            else:
                start = end = len(source)
                replacement, kind = ' ', 'append-trivia'
            source = source[:start] + replacement + source[end:]
            accepted = phase not in (2, 3)
            # These templates have a final closing brace removed only at phases 2/3;
            # their expected rejection point is EOF. No parser computes the oracle.
            tail = source.rsplit('\n', 1)[-1]
            rows.append('\t'.join(map(str, [language, step, ENTRIES[language], start, end,
                hex_text(replacement), hex_text(source), str(accepted).lower(), len(source),
                len(source.encode('utf-16-le')) // 2, source.count('\n'),
                len(tail.encode('utf-16-le')) // 2, step, 'UNSUPPORTED', kind, UNSUPPORTED[language]])))
    return '\n'.join(rows) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    options = parser.parse_args()
    target = ROOT / 'docs/fixtures/language-profile-edit-replay/edits.tsv'
    expected = author()
    if options.check:
        if target.read_text() != expected:
            raise SystemExit('profile edit replay fixture drift')
    else:
        temporary = target.with_suffix('.tsv.tmp')
        temporary.write_text(expected)
        temporary.replace(target)


if __name__ == '__main__':
    main()
