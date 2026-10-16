use super::*;

#[derive(Clone, Copy)]
struct Token {
    c: char,
    quoted: bool,
    offset: usize,
}
struct Parser {
    tokens: Vec<Token>,
    at: usize,
    end: usize,
    flags: Flags,
    nodes: usize,
    quote: bool,
    set_work: usize,
    limits: CompileLimits,
}
enum Escape {
    Scalar(char),
    Set(Set),
    Anchor(Anchor),
}

pub(super) fn parse(pattern: &str, mut limits: CompileLimits) -> Result<Node, CompileError> {
    limits.max_depth = limits.max_depth.min(64);
    let chars: Vec<_> = pattern
        .chars()
        .take(limits.max_pattern_codepoints.saturating_add(1))
        .collect();
    if chars.len() > limits.max_pattern_codepoints {
        return Err(CompileError {
            kind: CompileErrorKind::ResourceLimit,
            offset: limits.max_pattern_codepoints,
            message: "pattern size limit",
        });
    }
    let tokens = chars
        .iter()
        .enumerate()
        .map(|(offset, &c)| Token {
            c,
            quoted: false,
            offset,
        })
        .collect();
    let mut parser = Parser {
        tokens,
        at: 0,
        end: chars.len(),
        flags: Flags::default(),
        nodes: 0,
        quote: false,
        set_work: 0,
        limits,
    };
    let node = parser.alternatives(0)?;
    if parser.peek().is_some() {
        return Err(parser.error(CompileErrorKind::Syntax, "unmatched closing parenthesis"));
    }
    Ok(node)
}
impl Parser {
    fn error(&self, kind: CompileErrorKind, message: &'static str) -> CompileError {
        CompileError {
            kind,
            offset: self.tokens.get(self.at).map_or(self.end, |t| t.offset),
            message,
        }
    }
    fn syntax<T>(&self, message: &'static str) -> Result<T, CompileError> {
        Err(self.error(CompileErrorKind::Syntax, message))
    }
    fn unsupported<T>(&self, message: &'static str) -> Result<T, CompileError> {
        Err(self.error(CompileErrorKind::Unsupported, message))
    }
    fn budget(&mut self, depth: usize) -> Result<(), CompileError> {
        self.nodes += 1;
        if self.nodes > self.limits.max_nodes || depth > self.limits.max_depth {
            return Err(self.error(
                CompileErrorKind::ResourceLimit,
                "pattern depth or expression size limit",
            ));
        }
        Ok(())
    }
    fn ignored(&mut self) {
        loop {
            if self.quote {
                if self.tokens.get(self.at).is_some_and(|t| t.c == '\\')
                    && self.tokens.get(self.at + 1).is_some_and(|t| t.c == 'E')
                {
                    self.at += 2;
                    self.quote = false;
                    continue;
                }
                return;
            }
            if self.tokens.get(self.at).is_some_and(|t| t.c == '\\')
                && self.tokens.get(self.at + 1).is_some_and(|t| t.c == 'Q')
            {
                self.at += 2;
                self.quote = true;
                continue;
            }
            if !self.flags.comments {
                return;
            }
            match self.tokens.get(self.at).copied() {
                Some(Token { c, .. }) if ascii_space(c) => self.at += 1,
                Some(Token { c: '#', .. }) => {
                    self.at += 1;
                    while self
                        .tokens
                        .get(self.at)
                        .is_some_and(|t| !terminator(t.c, self.flags.unix))
                    {
                        self.at += 1;
                    }
                }
                _ => return,
            }
        }
    }
    fn peek(&mut self) -> Option<Token> {
        self.ignored();
        self.tokens.get(self.at).copied().map(|mut t| {
            t.quoted = self.quote;
            t
        })
    }
    // Inside an escape/count, COMMENTS may skip source whitespace, but \Q
    // must not begin another regex construct (for example \x\Q41\E is invalid).
    fn escape_peek(&mut self) -> Option<Token> {
        if self.flags.comments {
            loop {
                match self.tokens.get(self.at).copied() {
                    Some(Token { c, .. }) if ascii_space(c) => self.at += 1,
                    Some(Token { c: '#', .. }) => {
                        self.at += 1;
                        while self
                            .tokens
                            .get(self.at)
                            .is_some_and(|t| !terminator(t.c, self.flags.unix))
                        {
                            self.at += 1;
                        }
                    }
                    _ => break,
                }
            }
        }
        self.tokens.get(self.at).copied()
    }
    fn escape_next(&mut self) -> Option<Token> {
        let result = self.escape_peek();
        if result.is_some() {
            self.at += 1;
        }
        result
    }
    fn escape_eat(&mut self, c: char) -> bool {
        if self.escape_peek().is_some_and(|t| t.c == c) {
            self.at += 1;
            true
        } else {
            false
        }
    }
    fn intersection(&mut self) -> bool {
        let old = (self.at, self.quote);
        let yes = self.eat('&') && self.eat('&');
        self.at = old.0;
        self.quote = old.1;
        yes
    }
    fn next(&mut self) -> Option<Token> {
        let value = self.peek();
        if value.is_some() {
            self.at += 1;
        }
        value
    }
    fn raw(&mut self) -> Option<Token> {
        let value = self.tokens.get(self.at).copied();
        if value.is_some() {
            self.at += 1;
        }
        value
    }
    fn eat(&mut self, c: char) -> bool {
        if self.peek().is_some_and(|t| t.c == c && !t.quoted) {
            self.at += 1;
            true
        } else {
            false
        }
    }
    fn require(&mut self, c: char, message: &'static str) -> Result<(), CompileError> {
        if self.eat(c) {
            Ok(())
        } else {
            self.syntax(message)
        }
    }
    fn alternatives(&mut self, depth: usize) -> Result<Node, CompileError> {
        self.budget(depth)?;
        let mut branches = vec![self.sequence(depth)?];
        while self.eat('|') {
            branches.push(self.sequence(depth)?);
        }
        Ok(if branches.len() == 1 {
            branches.remove(0)
        } else {
            Node::Choice(branches)
        })
    }
    fn sequence(&mut self, depth: usize) -> Result<Node, CompileError> {
        let mut nodes = Vec::new();
        while let Some(token) = self.peek() {
            if !token.quoted && matches!(token.c, ')' | '|') {
                break;
            }
            if let Some(atom) = self.atom(depth)? {
                nodes.push(self.quantifier(atom, depth)?);
            }
        }
        Ok(match nodes.len() {
            0 => Node::Empty,
            1 => nodes.remove(0),
            _ => Node::Sequence(nodes),
        })
    }
    fn atom(&mut self, depth: usize) -> Result<Option<Node>, CompileError> {
        self.budget(depth)?;
        let token = self.next().unwrap();
        if token.quoted {
            return Ok(Some(Node::Set(Set::single(
                token.c,
                self.flags.insensitive,
            ))));
        }
        Ok(Some(match token.c {
            '(' => return self.group(depth + 1),
            '[' => Node::Set(self.class(depth + 1)?),
            '.' => Node::Set(if self.flags.dotall {
                Set::all()
            } else {
                let mut excluded = Set::single('\n', false);
                if !self.flags.unix {
                    for c in ['\r', '\u{85}', '\u{2028}', '\u{2029}'] {
                        excluded = excluded.union(Set::single(c, false));
                    }
                }
                excluded.complement()
            }),
            '^' => Node::Anchor(Anchor::Begin, self.flags),
            '$' => Node::Anchor(Anchor::End, self.flags),
            '\\' => match self.escape(false)? {
                Escape::Scalar(c) => Node::Set(Set::single(c, self.flags.insensitive)),
                Escape::Set(set) => Node::Set(set),
                Escape::Anchor(anchor) => Node::Anchor(anchor, self.flags),
            },
            '*' | '+' | '?' | '{' => return self.syntax("dangling or malformed quantifier"),
            c => Node::Set(Set::single(c, self.flags.insensitive)),
        }))
    }
    fn group(&mut self, depth: usize) -> Result<Option<Node>, CompileError> {
        self.budget(depth)?;
        let old = self.flags;
        let mut atomic = false;
        if self.eat('?') {
            if self.eat(':') {
            } else if self.eat('>') {
                atomic = true;
            } else if self
                .peek()
                .is_some_and(|t| !t.quoted && matches!(t.c, '=' | '!' | '<'))
            {
                return self.unsupported("lookaround and named groups are not implemented");
            } else {
                let mut enabled = true;
                let mut any = false;
                let mut minus = false;
                loop {
                    let Some(t) = self.peek() else {
                        return self.syntax("unclosed flags group");
                    };
                    if !t.quoted && t.c == '-' && !minus {
                        self.at += 1;
                        enabled = false;
                        minus = true;
                        continue;
                    }
                    if !t.quoted && matches!(t.c, 'u' | 'U' | 'c') {
                        return self.unsupported(
                            "Unicode or canonical-equivalence regex flags are not implemented",
                        );
                    }
                    let flag = if t.quoted {
                        None
                    } else {
                        match t.c {
                            'i' => Some(&mut self.flags.insensitive),
                            'd' => Some(&mut self.flags.unix),
                            'm' => Some(&mut self.flags.multiline),
                            's' => Some(&mut self.flags.dotall),
                            'x' => Some(&mut self.flags.comments),
                            _ => None,
                        }
                    };
                    if let Some(flag) = flag {
                        *flag = enabled;
                        self.at += 1;
                        any = true;
                    } else {
                        break;
                    }
                }
                if self.eat(')') {
                    return Ok(None);
                }
                if !any && !minus {
                    return self.syntax("unknown group construct or inline flag");
                }
                self.require(':', "unknown inline flag or missing flag-group colon")?;
            }
        }
        let body = self.alternatives(depth)?;
        self.require(')', "unclosed group")?;
        self.flags = old;
        Ok(Some(if atomic {
            Node::Atomic(Box::new(body))
        } else {
            body
        }))
    }
    fn quantifier(&mut self, body: Node, depth: usize) -> Result<Node, CompileError> {
        let (min, max) = if self.eat('*') {
            (0, None)
        } else if self.eat('+') {
            (1, None)
        } else if self.eat('?') {
            (0, Some(1))
        } else if self.eat('{') {
            // Java requires the first minimum digit immediately after the opening brace.
            let min = self.count()?;
            let max = if self.escape_eat(',') {
                if self
                    .escape_peek()
                    .is_some_and(|t| !t.quoted && t.c.is_ascii_digit())
                {
                    Some(self.count()?)
                } else {
                    None
                }
            } else {
                Some(min)
            };
            if !self.escape_eat('}') {
                return self.syntax("unclosed or malformed repetition");
            }
            if max.is_some_and(|max| max < min) {
                return self.syntax("repetition maximum is below minimum");
            }
            (min, max)
        } else {
            return Ok(body);
        };
        self.budget(depth)?;
        let reluctant = self.eat('?');
        let possessive = !reluctant && self.eat('+');
        let repeat = Node::Repeat {
            body: Box::new(body),
            min,
            max,
            reluctant,
        };
        Ok(if possessive {
            Node::Atomic(Box::new(repeat))
        } else {
            repeat
        })
    }
    fn count(&mut self) -> Result<usize, CompileError> {
        if !self
            .tokens
            .get(self.at)
            .is_some_and(|t| !t.quoted && t.c.is_ascii_digit())
        {
            return self.syntax("missing repetition count");
        }
        let mut count = 0usize;
        while let Some(t) = self.escape_peek() {
            if t.quoted || !t.c.is_ascii_digit() {
                break;
            }
            self.at += 1;
            count = count
                .checked_mul(10)
                .and_then(|n| n.checked_add(t.c as usize - '0' as usize))
                .filter(|n| *n <= i32::MAX as usize)
                .ok_or_else(|| self.error(CompileErrorKind::Syntax, "repetition count overflow"))?;
        }
        Ok(count)
    }
    fn set_budget(&mut self, left: &Set, right: &Set) -> Result<(), CompileError> {
        self.set_work = self
            .set_work
            .saturating_add(left.0.len().saturating_add(right.0.len()).max(1));
        if self.set_work > self.limits.max_set_work {
            return Err(self.error(
                CompileErrorKind::ResourceLimit,
                "character-class set operation limit",
            ));
        }
        Ok(())
    }
    fn class(&mut self, depth: usize) -> Result<Set, CompileError> {
        self.budget(depth)?;
        let negated = self.eat('^');
        let mut first = true;
        let mut result: Option<Set> = None;
        loop {
            let mut union = Set::empty();
            let mut any = false;
            loop {
                let Some(token) = self.peek() else {
                    return self.syntax("unclosed character class");
                };
                if !first && !token.quoted && token.c == ']' {
                    break;
                }
                if !token.quoted && token.c == '&' && self.intersection() {
                    break;
                }
                if result.is_some() && !any && !token.quoted && token.c == '&' {
                    return self.unsupported(
                        "degenerate character-class ampersand operand is not implemented",
                    );
                }
                first = false;
                self.budget(depth)?;
                let (mut set, single) = self.class_atom(depth)?;
                any = true;
                if single.is_some() && self.eat('-') {
                    if self.peek().is_some_and(|t| !t.quoted && t.c == ']') {
                        set = set.union(Set::single('-', self.flags.insensitive));
                    } else {
                        let (_, end) = self.class_atom(depth)?;
                        let (Some(start), Some(end)) = (single, end) else {
                            return self.syntax("illegal character range");
                        };
                        if start > end {
                            return self.syntax("reversed character range");
                        }
                        set = Set::range(start, end, self.flags.insensitive);
                    }
                }
                self.set_budget(&union, &set)?;
                union = union.union(set);
            }
            if !any {
                return self
                    .unsupported("degenerate character-class intersection is not implemented");
            }
            result = Some(match result {
                None => union,
                Some(previous) => {
                    self.set_budget(&previous, &union)?;
                    previous.intersect(union)
                }
            });
            if self.eat(']') {
                break;
            }
            self.require('&', "invalid character class operator")?;
            self.require('&', "invalid character class intersection")?;
        }
        let result = result.unwrap();
        if negated {
            self.set_budget(&result, &Set::empty())?;
        }
        Ok(if negated { result.complement() } else { result })
    }
    fn class_atom(&mut self, depth: usize) -> Result<(Set, Option<char>), CompileError> {
        let Some(token) = self.next() else {
            return self.syntax("unclosed character class");
        };
        if !token.quoted && token.c == '[' {
            return Ok((self.class(depth + 1)?, None));
        }
        if !token.quoted && token.c == '\\' {
            return match self.escape(true)? {
                Escape::Scalar(c) => Ok((Set::single(c, self.flags.insensitive), Some(c))),
                Escape::Set(set) => Ok((set, None)),
                Escape::Anchor(_) => self.syntax("anchor in character class"),
            };
        }
        Ok((Set::single(token.c, self.flags.insensitive), Some(token.c)))
    }
    fn escape(&mut self, in_class: bool) -> Result<Escape, CompileError> {
        let Some(token) = self.raw() else {
            return self.syntax("trailing backslash");
        };
        let c = token.c;
        if let Some(set) = Set::ascii(c) {
            return Ok(Escape::Set(set));
        }
        let scalar = match c {
            't' => '\t',
            'n' => '\n',
            'r' => '\r',
            'f' => '\u{c}',
            'a' => '\u{7}',
            'e' => '\u{1b}',
            'c' => {
                let Some(next) = self.escape_next() else {
                    return self.syntax("missing control character");
                };
                if !next.c.is_ascii() {
                    return self.unsupported("non-ASCII control escape is not implemented");
                }
                char::from_u32(next.c as u32 ^ 64).unwrap()
            }
            'x' => {
                if self.escape_eat('{') {
                    let mut value = 0u32;
                    let mut digits = 0;
                    while let Some(t) = self.escape_peek() {
                        if t.quoted || t.c == '}' {
                            break;
                        }
                        let Some(d) = t.c.to_digit(16) else {
                            return self.syntax("invalid hexadecimal escape");
                        };
                        self.at += 1;
                        digits += 1;
                        value = value
                            .checked_mul(16)
                            .and_then(|n| n.checked_add(d))
                            .ok_or_else(|| {
                                self.error(CompileErrorKind::Syntax, "hexadecimal escape overflow")
                            })?;
                    }
                    if digits == 0 {
                        return self.syntax("empty hexadecimal escape");
                    }
                    if !self.escape_eat('}') {
                        return self.syntax("unclosed hexadecimal escape");
                    }
                    self.scalar(value)?
                } else {
                    let value = self.hex(2)?;
                    self.scalar(value)?
                }
            }
            'u' => {
                let value = self.hex(4)?;
                if (0xd800..=0xdbff).contains(&value) {
                    if !self.eat('\\') || !self.eat('u') {
                        return self.unsupported(
                            "unpaired UTF-16 surrogate pattern is not representable in Rust str",
                        );
                    }
                    let low = self.hex(4)?;
                    if !(0xdc00..=0xdfff).contains(&low) {
                        return self.unsupported(
                            "unpaired UTF-16 surrogate pattern is not representable in Rust str",
                        );
                    }
                    self.scalar(0x10000 + ((value - 0xd800) << 10) + low - 0xdc00)?
                } else {
                    self.scalar(value)?
                }
            }
            '0' => {
                let Some(first) = self.escape_next() else {
                    return self.syntax("missing octal digit");
                };
                if first.quoted || !matches!(first.c, '0'..='7') {
                    return self.syntax("invalid octal escape");
                }
                let mut value = first.c as u32 - '0' as u32;
                for count in 0..2 {
                    if count == 1 && first.c > '3' {
                        break;
                    }
                    if let Some(next) = self
                        .escape_peek()
                        .filter(|t| !t.quoted && matches!(t.c, '0'..='7'))
                    {
                        value = value * 8 + next.c as u32 - '0' as u32;
                        self.at += 1;
                    } else {
                        break;
                    }
                }
                char::from_u32(value).unwrap()
            }
            'p' | 'P' => {
                let mut name = String::new();
                if self.escape_eat('{') {
                    while let Some(t) = self.escape_next() {
                        if !t.quoted && t.c == '}' {
                            break;
                        }
                        name.push(t.c);
                    }
                    if self
                        .tokens
                        .get(self.at.wrapping_sub(1))
                        .is_none_or(|t| t.c != '}')
                    {
                        return self.syntax("unclosed property escape");
                    }
                } else {
                    let Some(t) = self.escape_next() else {
                        return self.syntax("missing property name");
                    };
                    name.push(t.c);
                }
                if name.is_empty() {
                    return self.syntax("empty property name");
                }
                let Some(set) = Set::posix(&name, self.flags.insensitive) else {
                    return self
                        .unsupported("Unicode or Java character property is not implemented");
                };
                return Ok(Escape::Set(if c == 'P' { set.complement() } else { set }));
            }
            'A' | 'G' | 'z' | 'Z' if !in_class => {
                return Ok(Escape::Anchor(match c {
                    'A' | 'G' => Anchor::AbsoluteBegin,
                    'z' => Anchor::AbsoluteEnd,
                    _ => Anchor::FinalEnd,
                }));
            }
            'b' | 'B' if !in_class => {
                return self.unsupported(
                    "Unicode-dependent word and grapheme boundaries are not implemented",
                )
            }
            '1'..='9' | 'k' => return self.unsupported("regex backreferences are not implemented"),
            'R' | 'X' | 'h' | 'H' | 'v' | 'V' | 'N' => {
                return self
                    .unsupported("Unicode regex matcher or named character is not implemented")
            }
            c if c.is_ascii_alphabetic() => {
                return self.syntax("unknown or misplaced alphabetic escape")
            }
            c => c,
        };
        Ok(Escape::Scalar(scalar))
    }
    fn hex(&mut self, digits: usize) -> Result<u32, CompileError> {
        let mut value = 0;
        for _ in 0..digits {
            let Some(token) = self.escape_next() else {
                return self.syntax("incomplete hexadecimal escape");
            };
            let Some(d) = token.c.to_digit(16).filter(|_| !token.quoted) else {
                return self.syntax("invalid hexadecimal escape");
            };
            value = value * 16 + d;
        }
        Ok(value)
    }
    fn scalar(&self, value: u32) -> Result<char, CompileError> {
        if (0xd800..=0xdfff).contains(&value) {
            return self
                .unsupported("unpaired UTF-16 surrogate pattern is not representable in Rust str");
        }
        char::from_u32(value)
            .ok_or_else(|| self.error(CompileErrorKind::Syntax, "code point outside Unicode range"))
    }
}
