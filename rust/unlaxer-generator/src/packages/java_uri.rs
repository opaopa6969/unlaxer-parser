//! `java.net.URI.create` (JDK 17/21 RFC 2396 parser) over UTF-16 units, for HTTPS
//! sources that a Rust string cannot hold. It only classifies the URL the way
//! `UBNFPackageFetcher.fetch` does before credentials; it never builds a request.
//! Ordinary scalar URLs keep the existing `fetch(&str)` path.

const L_DIGIT: u64 = 0x3FF000000000000;
const H_ALPHA: u64 = 0x7FFFFFE | 0x7FFFFFE00000000;
const L_HEX: u64 = L_DIGIT;
const H_HEX: u64 = 0x7E0000007E;
const L_MARK: u64 = 0x678200000000;
const H_MARK: u64 = 0x4000000080000000;
const L_UNRESERVED: u64 = L_DIGIT | L_MARK;
const H_UNRESERVED: u64 = H_ALPHA | H_MARK;
const L_RESERVED: u64 = 0xAC00985000000000;
const H_RESERVED: u64 = 0x28000001;
const L_ESCAPED: u64 = 1;
const L_URIC: u64 = L_RESERVED | L_UNRESERVED | L_ESCAPED;
const H_URIC: u64 = H_RESERVED | H_UNRESERVED;
const L_PCHAR: u64 = L_UNRESERVED | L_ESCAPED | 0x2400185000000000;
const H_PCHAR: u64 = H_UNRESERVED | 0x1;
const L_PATH: u64 = L_PCHAR | 0x800800000000000;
const H_PATH: u64 = H_PCHAR;
const L_DASH: u64 = 0x200000000000;
const L_DOT: u64 = 0x400000000000;
const L_USERINFO: u64 = L_UNRESERVED | L_ESCAPED | 0x2C00185000000000;
const H_USERINFO: u64 = H_UNRESERVED;
const L_REG_NAME: u64 = L_UNRESERVED | L_ESCAPED | 0x2C00185000000000;
const H_REG_NAME: u64 = H_UNRESERVED | 0x1;
const L_SERVER: u64 = L_USERINFO | L_DIGIT | L_DASH | 0x400400000000000;
const H_SERVER: u64 = H_USERINFO | H_ALPHA | 0x28000001;
const L_SERVER_PERCENT: u64 = L_SERVER | 0x2000000000;
const H_SERVER_PERCENT: u64 = H_SERVER;
const L_SCHEME: u64 = L_DIGIT | 0x680000000000;
const H_SCHEME: u64 = H_ALPHA;
const L_SCOPE_ID: u64 = L_DIGIT | 0x400000000000;
const H_SCOPE_ID: u64 = H_ALPHA | 0x80000000;

/// What `UBNFPackageFetcher.fetch` decides before reading credentials.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum Https {
    /// `URI.create` threw: `invalid HTTPS artifact URL`.
    Invalid,
    /// Parsed, but not an exact HTTPS origin without userinfo/query/fragment.
    Omit,
    /// Credentials are looked up for this origin.
    Origin(String),
}

pub(super) fn classify(units: &[u16]) -> Https {
    let mut uri = Parser {
        input: units,
        scheme: None,
        host: None,
        user_info: false,
        query: false,
        fragment: false,
        port: -1,
        ipv6_bytes: 0,
    };
    if uri.parse().is_err() {
        return Https::Invalid;
    }
    let ascii = |range: (usize, usize)| -> Option<String> {
        units[range.0..range.1]
            .iter()
            .map(|u| u8::try_from(*u).ok().filter(u8::is_ascii).map(char::from))
            .collect()
    };
    let host = uri.host.and_then(ascii).filter(|host| {
        !host.is_empty()
            && host
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b".-".contains(&c))
    });
    match host {
        Some(host)
            if uri.scheme.and_then(ascii).as_deref() == Some("https")
                && !uri.user_info
                && !uri.query
                && !uri.fragment
                && uri.port != 0
                && uri.port <= 65535 =>
        {
            let port = if uri.port == -1 || uri.port == 443 {
                String::new()
            } else {
                format!(":{}", uri.port)
            };
            Https::Origin(format!("https://{}{port}", host.to_ascii_lowercase()))
        }
        _ => Https::Omit,
    }
}

// Character.isSpaceChar (Zs/Zl/Zp; unchanged from Unicode 6.3 through JDK 21's 15.0).
fn space_char(c: u16) -> bool {
    matches!(
        c,
        0x20 | 0xa0 | 0x1680 | 0x2000..=0x200a | 0x2028 | 0x2029 | 0x202f | 0x205f | 0x3000
    )
}

fn matches(c: u16, low: u64, high: u64) -> bool {
    match c {
        0 => false,
        1..=63 => (1u64 << c) & low != 0,
        64..=127 => (1u64 << (c - 64)) & high != 0,
        _ => false,
    }
}

type Step<T> = Result<T, ()>;

struct Parser<'a> {
    input: &'a [u16],
    scheme: Option<(usize, usize)>,
    host: Option<(usize, usize)>,
    user_info: bool,
    query: bool,
    fragment: bool,
    port: i64,
    ipv6_bytes: u32,
}

impl Parser<'_> {
    fn at(&self, start: usize, end: usize, c: u8) -> bool {
        start < end && self.input[start] == u16::from(c)
    }

    fn at_str(&self, start: usize, end: usize, s: &[u8]) -> bool {
        end.checked_sub(start).is_some_and(|room| room >= s.len())
            && self.input[start..start + s.len()]
                .iter()
                .zip(s)
                .all(|(u, c)| *u == u16::from(*c))
    }

    fn scan_char(&self, start: usize, end: usize, c: u8) -> usize {
        if self.at(start, end, c) {
            start + 1
        } else {
            start
        }
    }

    /// `scan(start, end, err, stop)`: `None` is Java's -1.
    fn scan_until(&self, start: usize, end: usize, err: &[u8], stop: &[u8]) -> Option<usize> {
        let mut p = start;
        while p < end {
            let c = self.input[p];
            if err.iter().any(|e| u16::from(*e) == c) {
                return None;
            }
            if stop.iter().any(|s| u16::from(*s) == c) {
                break;
            }
            p += 1;
        }
        Some(p)
    }

    fn scan_stop(&self, start: usize, end: usize, stop: &[u8]) -> usize {
        self.scan_until(start, end, b"", stop).unwrap()
    }

    fn scan_escape(&self, p: usize, n: usize, c: u16) -> Step<usize> {
        if c == u16::from(b'%') {
            if p + 3 <= n
                && matches(self.input[p + 1], L_HEX, H_HEX)
                && matches(self.input[p + 2], L_HEX, H_HEX)
            {
                return Ok(p + 3);
            }
            return Err(()); // Malformed escape pair
        }
        // Visible non-US-ASCII units, including each unpaired surrogate.
        if c > 128 && !space_char(c) && !(0x7f..=0x9f).contains(&c) {
            return Ok(p + 1);
        }
        Ok(p)
    }

    fn scan(&self, start: usize, n: usize, low: u64, high: u64) -> Step<usize> {
        let mut p = start;
        while p < n {
            let c = self.input[p];
            if matches(c, low, high) {
                p += 1;
                continue;
            }
            if low & L_ESCAPED != 0 {
                let q = self.scan_escape(p, n, c)?;
                if q > p {
                    p = q;
                    continue;
                }
            }
            break;
        }
        Ok(p)
    }

    fn check(&self, start: usize, end: usize, low: u64, high: u64) -> Step<()> {
        if self.scan(start, end, low, high)? < end {
            return Err(());
        }
        Ok(())
    }

    fn integer(&self, start: usize, end: usize) -> Step<i64> {
        // Integer.parseInt on ASCII digits: overflow is an exception, not a value.
        let mut value: i64 = 0;
        for unit in &self.input[start..end] {
            value = value * 10 + i64::from(unit - u16::from(b'0'));
            if value > i64::from(i32::MAX) {
                return Err(());
            }
        }
        Ok(value)
    }

    fn parse(&mut self) -> Step<()> {
        let n = self.input.len();
        let mut p;
        match self.scan_until(0, n, b"/?#", b":") {
            Some(colon) if self.at(colon, n, b':') => {
                if colon == 0 {
                    return Err(());
                }
                self.check(0, 1, 0, H_ALPHA)?;
                self.check(1, colon, L_SCHEME, H_SCHEME)?;
                self.scheme = Some((0, colon));
                p = colon + 1;
                if self.at(p, n, b'/') {
                    p = self.parse_hierarchical(p, n)?;
                } else {
                    let q = self.scan_stop(p, n, b"#");
                    if q <= p {
                        return Err(());
                    }
                    self.check(p, q, L_URIC, H_URIC)?;
                    p = q;
                }
            }
            _ => p = self.parse_hierarchical(0, n)?,
        }
        if self.at(p, n, b'#') {
            self.check(p + 1, n, L_URIC, H_URIC)?;
            self.fragment = true;
            p = n;
        }
        if p < n {
            return Err(());
        }
        Ok(())
    }

    fn parse_hierarchical(&mut self, start: usize, n: usize) -> Step<usize> {
        let mut p = start;
        if self.at(p, n, b'/') && self.at(p + 1, n, b'/') {
            p += 2;
            let q = self.scan_stop(p, n, b"/?#");
            if q > p {
                p = self.parse_authority(p, q)?;
            } else if q >= n {
                return Err(()); // Expected authority
            }
        }
        let q = self.scan_stop(p, n, b"?#");
        self.check(p, q, L_PATH, H_PATH)?;
        p = q;
        if self.at(p, n, b'?') {
            p += 1;
            let q = self.scan_stop(p, n, b"#");
            self.check(p, q, L_URIC, H_URIC)?;
            self.query = true;
            p = q;
        }
        Ok(p)
    }

    fn parse_authority(&mut self, p: usize, n: usize) -> Step<usize> {
        let server_chars = if self.scan_stop(p, n, b"]") > p {
            self.scan(p, n, L_SERVER_PERCENT, H_SERVER_PERCENT)? == n
        } else {
            self.scan(p, n, L_SERVER, H_SERVER)? == n
        };
        let reg_chars = self.scan(p, n, L_REG_NAME, H_REG_NAME)? == n;
        if reg_chars && !server_chars {
            return Ok(n); // registry-based: getHost() is null
        }
        if server_chars && matches!(self.parse_server(p, n), Ok(q) if q == n) {
            return Ok(n);
        }
        // A failed server parse is undone; a registry name may still accept it.
        self.user_info = false;
        self.host = None;
        self.port = -1;
        if reg_chars {
            Ok(n)
        } else {
            Err(())
        }
    }

    fn parse_server(&mut self, start: usize, n: usize) -> Step<usize> {
        let mut p = start;
        if let Some(q) = self.scan_until(p, n, b"/?#", b"@") {
            if self.at(q, n, b'@') {
                self.check(p, q, L_USERINFO, H_USERINFO)?;
                self.user_info = true;
                p = q + 1;
            }
        }
        if self.at(p, n, b'[') {
            p += 1;
            match self.scan_until(p, n, b"/?#", b"]") {
                Some(q) if q > p && self.at(q, n, b']') => {
                    let r = self.scan_stop(p, q, b"%");
                    if r > p {
                        self.parse_ipv6(p, r)?;
                        if r + 1 == q {
                            return Err(()); // scope id expected
                        }
                        if r + 1 < q {
                            self.check(r + 1, q, L_SCOPE_ID, H_SCOPE_ID)?;
                        }
                    } else {
                        self.parse_ipv6(p, q)?;
                    }
                    self.host = Some((p - 1, q + 1));
                    p = q + 1;
                }
                _ => return Err(()),
            }
        } else {
            let q = self.parse_ipv4_address(p, n);
            p = match q {
                Some(q) if q > p => q,
                _ => self.parse_hostname(p, n)?,
            };
        }
        if self.at(p, n, b':') {
            p += 1;
            let q = self.scan_stop(p, n, b"/");
            if q > p {
                self.check(p, q, L_DIGIT, 0)?;
                self.port = self.integer(p, q)?;
                p = q;
            }
        }
        if p < n {
            return Err(());
        }
        Ok(p)
    }

    fn scan_byte(&self, start: usize, n: usize) -> Step<usize> {
        let q = self.scan(start, n, L_DIGIT, 0)?;
        if q <= start {
            return Ok(q);
        }
        Ok(if self.integer(start, q)? > 255 {
            start
        } else {
            q
        })
    }

    /// `None` is Java's -1.
    fn scan_ipv4(&self, start: usize, n: usize, strict: bool) -> Step<Option<usize>> {
        let mut p = start;
        let m = self.scan(p, n, L_DIGIT | L_DOT, 0)?;
        if m <= p || (strict && m != n) {
            return Ok(None);
        }
        for octet in 0..4 {
            if octet > 0 {
                let q = self.scan_char(p, m, b'.');
                if q <= p {
                    return Err(());
                }
                p = q;
            }
            let q = self.scan_byte(p, m)?;
            if q <= p {
                return Err(());
            }
            p = q;
        }
        if p < m {
            return Err(()); // Malformed IPv4 address
        }
        Ok(Some(p))
    }

    fn take_ipv4(&self, start: usize, n: usize) -> Step<usize> {
        match self.scan_ipv4(start, n, true)? {
            Some(p) if p > start => Ok(p),
            _ => Err(()),
        }
    }

    fn parse_ipv4_address(&mut self, start: usize, n: usize) -> Option<usize> {
        let mut p = self.scan_ipv4(start, n, false).ok()??;
        if p > start && p < n && self.input[p] != u16::from(b':') {
            return None;
        }
        if p > start {
            self.host = Some((start, p));
        } else {
            p = start;
        }
        Some(p)
    }

    fn parse_hostname(&mut self, start: usize, n: usize) -> Step<usize> {
        let mut p = start;
        let mut last = None;
        loop {
            let q = self.scan(p, n, L_DIGIT, H_ALPHA)?;
            if q <= p {
                break;
            }
            last = Some(p);
            p = q;
            let q = self.scan(p, n, L_DIGIT | L_DASH, H_ALPHA)?;
            if q > p {
                if self.input[q - 1] == u16::from(b'-') {
                    return Err(());
                }
                p = q;
            }
            let q = self.scan_char(p, n, b'.');
            if q <= p {
                break;
            }
            p = q;
            if p >= n {
                break;
            }
        }
        if p < n && !self.at(p, n, b':') {
            return Err(());
        }
        let last = last.ok_or(())?;
        if last > start && !matches(self.input[last], 0, H_ALPHA) {
            return Err(());
        }
        self.host = Some((start, p));
        Ok(p)
    }

    fn parse_ipv6(&mut self, start: usize, n: usize) -> Step<usize> {
        let mut p = start;
        let mut compressed = false;
        match self.scan_hex_seq(p, n)? {
            Some(q) if q > p => {
                p = q;
                if self.at_str(p, n, b"::") {
                    compressed = true;
                    p = self.scan_hex_post(p + 2, n)?;
                } else if self.at(p, n, b':') {
                    p = self.take_ipv4(p + 1, n)?;
                    self.ipv6_bytes += 4;
                }
            }
            _ if self.at_str(p, n, b"::") => {
                compressed = true;
                p = self.scan_hex_post(p + 2, n)?;
            }
            _ => {}
        }
        if p < n
            || self.ipv6_bytes > 16
            || (!compressed && self.ipv6_bytes < 16)
            || (compressed && self.ipv6_bytes == 16)
        {
            return Err(());
        }
        Ok(p)
    }

    fn scan_hex_post(&mut self, start: usize, n: usize) -> Step<usize> {
        if start == n {
            return Ok(start);
        }
        let mut p = start;
        match self.scan_hex_seq(p, n)? {
            Some(q) if q > p => {
                p = q;
                if self.at(p, n, b':') {
                    p = self.take_ipv4(p + 1, n)?;
                    self.ipv6_bytes += 4;
                }
            }
            _ => {
                p = self.take_ipv4(p, n)?;
                self.ipv6_bytes += 4;
            }
        }
        Ok(p)
    }

    /// `None` is Java's -1.
    fn scan_hex_seq(&mut self, start: usize, n: usize) -> Step<Option<usize>> {
        let mut q = self.scan(start, n, L_HEX, H_HEX)?;
        if q <= start || self.at(q, n, b'.') {
            return Ok(None);
        }
        if q > start + 4 {
            return Err(());
        }
        self.ipv6_bytes += 2;
        let mut p = q;
        while p < n {
            if !self.at(p, n, b':') || self.at(p + 1, n, b':') {
                break;
            }
            p += 1;
            q = self.scan(p, n, L_HEX, H_HEX)?;
            if q <= p {
                return Err(());
            }
            if self.at(q, n, b'.') {
                p -= 1;
                break;
            }
            if q > p + 4 {
                return Err(());
            }
            self.ipv6_bytes += 2;
            p = q;
        }
        Ok(Some(p))
    }
}

#[cfg(test)]
mod tests {
    use super::{classify, Https};

    fn run(source: &str) -> Https {
        // `\u{e000}` stands for an unpaired high surrogate in these literals.
        let units: Vec<u16> = source
            .encode_utf16()
            .map(|u| if u == 0xe000 { 0xd800 } else { u })
            .collect();
        classify(&units)
    }

    #[test]
    fn unpaired_surrogate_urls_follow_java_uri_create() {
        let origin = |o: &str| Https::Origin(o.into());
        // Expected values are Oracle JDK 17.0.12 / 21.0.9 URI.create plus the
        // UBNFPackageFetcher host/userinfo/query/fragment/port checks.
        for (source, expected) in [
            (
                "https://localhost:1/a\u{e000}",
                origin("https://localhost:1"),
            ),
            ("https://LocalHost/a\u{e000}", origin("https://localhost")),
            (
                "https://localhost:443/a\u{e000}",
                origin("https://localhost"),
            ),
            (
                "https://localhost:0443/a\u{e000}",
                origin("https://localhost"),
            ),
            ("https://localhost:/a\u{e000}", origin("https://localhost")),
            ("https://1.2.3.4:8/a\u{e000}", origin("https://1.2.3.4:8")),
            (
                "https://localhost.:1/a\u{e000}",
                origin("https://localhost.:1"),
            ),
            (
                "https://localhost:1/a%41\u{e000}",
                origin("https://localhost:1"),
            ),
            (
                "https://localhost:1/a:@;\u{e000}",
                origin("https://localhost:1"),
            ),
            (
                "https://localhost:1/a\u{200b}\u{e000}",
                origin("https://localhost:1"),
            ),
            ("https://localhost:1/a[\u{e000}", Https::Invalid),
            ("https://localhost:1/a]\u{e000}", Https::Invalid),
            ("https://localhost:1/a%4\u{e000}", Https::Invalid),
            ("https://localhost:1/a%\u{e000}", Https::Invalid),
            ("https://localhost:1/a \u{e000}", Https::Invalid),
            ("https://localhost:1/a\u{a0}\u{e000}", Https::Invalid),
            ("https://localhost:1/a\u{3000}\u{e000}", Https::Invalid),
            ("https://localhost:1/a\u{85}\u{e000}", Https::Invalid),
            ("https://localhost:1/a\u{80}\u{e000}", Https::Invalid),
            ("https://localhost:1/a|\u{e000}", Https::Invalid),
            ("https://localhost:1/a\u{e000}#x#y", Https::Invalid),
            ("https://localhost:1/a\u{e000}?", Https::Omit),
            ("https://localhost:1/a\u{e000}#", Https::Omit),
            ("https://localhost:1/?a[\u{e000}", Https::Omit),
            ("https://localhost:1/#a[\u{e000}", Https::Omit),
            ("https://user@localhost:1/a\u{e000}", Https::Omit),
            ("https://\u{e000}@localhost:1/x", Https::Omit),
            ("https://\u{e000}@[::1]:1/x", Https::Omit),
            ("https://[::1]:1/a\u{e000}", Https::Omit),
            ("https://[\u{e000}]/x", Https::Invalid),
            ("https://[::1%\u{e000}]/x", Https::Invalid),
            ("https://\u{e000}[/x", Https::Invalid),
            ("https://\u{e000}%zz/x", Https::Invalid),
            ("https://\u{e000}", Https::Omit),
            ("https://loc\u{e000}alhost:1/x", Https::Omit),
            ("https://\u{e000}/#a#b", Https::Invalid),
            ("https://\u{e000}/a[", Https::Invalid),
            ("https://\u{e000}/a\u{a0}", Https::Invalid),
            ("https://\u{e000}@localhost:1/a[", Https::Invalid),
            ("https://localhost:0/a\u{e000}", Https::Omit),
            ("https://localhost:65536/a\u{e000}", Https::Omit),
            ("https://localhost:99999999999/a\u{e000}", Https::Omit),
            ("https://localhost:1a/\u{e000}", Https::Omit),
            ("https://localhost:+1/\u{e000}", Https::Omit),
            ("https://localhost:1;x/a\u{e000}", Https::Omit),
            ("https://-a:1/\u{e000}", Https::Omit),
            ("https://a-:1/\u{e000}", Https::Omit),
            ("https://localhost..:1/a\u{e000}", Https::Omit),
            ("https://a.1:1/\u{e000}", Https::Omit),
            ("https://999.1.1.1/\u{e000}", Https::Omit),
            ("https://a_b/\u{e000}", Https::Omit),
            ("https://%6Cocalhost:1/a\u{e000}", Https::Omit),
            ("https:\u{e000}", Https::Omit),
            ("https:/a\u{e000}", Https::Omit),
            ("https:///a\u{e000}", Https::Omit),
            ("https:\u{e000}//localhost:1/x", Https::Omit),
            ("https:#\u{e000}", Https::Invalid),
            ("https://\u{e000}:1/\u{e000}", Https::Omit),
        ] {
            assert_eq!(run(source), expected, "{source:?}");
        }
    }
}
