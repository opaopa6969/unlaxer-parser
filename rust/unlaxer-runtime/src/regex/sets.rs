/// Sorted disjoint scalar intervals. Set operations are resolved at compile time.
#[derive(Debug, Clone)]
pub(super) struct Set(pub(super) Vec<(u32, u32)>);
impl Set {
    pub(super) fn empty() -> Self {
        Self(Vec::new())
    }
    pub(super) fn all() -> Self {
        Self(vec![(0, 0xd7ff), (0xe000, 0x10ffff)])
    }
    pub(super) fn single(c: char, insensitive: bool) -> Self {
        Self::range(c, c, insensitive)
    }
    pub(super) fn range(start: char, end: char, insensitive: bool) -> Self {
        let lo = start as u32;
        let hi = end as u32;
        let mut result = Self(Vec::new());
        if lo <= 0xd7ff {
            result.0.push((lo, hi.min(0xd7ff)));
        }
        if hi >= 0xe000 {
            result.0.push((lo.max(0xe000), hi));
        }
        if insensitive {
            for c in 'A'..='Z' {
                if lo <= c as u32 && c as u32 <= hi {
                    result.0.push((c as u32 + 32, c as u32 + 32));
                }
                if lo <= c.to_ascii_lowercase() as u32 && c.to_ascii_lowercase() as u32 <= hi {
                    result.0.push((c as u32, c as u32));
                }
            }
            result.normalize();
        }
        result
    }
    fn normalize(&mut self) {
        self.0.sort_unstable();
        let mut count = 0;
        for index in 0..self.0.len() {
            let (start, end) = self.0[index];
            if count > 0 && start <= self.0[count - 1].1.saturating_add(1) {
                self.0[count - 1].1 = self.0[count - 1].1.max(end);
            } else {
                self.0[count] = (start, end);
                count += 1;
            }
        }
        self.0.truncate(count);
    }
    pub(super) fn contains(&self, c: char) -> bool {
        let cp = c as u32;
        self.0
            .binary_search_by(|&(lo, hi)| {
                if cp < lo {
                    std::cmp::Ordering::Greater
                } else if cp > hi {
                    std::cmp::Ordering::Less
                } else {
                    std::cmp::Ordering::Equal
                }
            })
            .is_ok()
    }
    pub(super) fn union(mut self, other: Self) -> Self {
        self.0.extend(other.0);
        self.normalize();
        self
    }
    pub(super) fn intersect(self, other: Self) -> Self {
        let (mut left, mut right) = (0, 0);
        let mut out = Vec::new();
        while left < self.0.len() && right < other.0.len() {
            let a = self.0[left];
            let b = other.0[right];
            if a.0.max(b.0) <= a.1.min(b.1) {
                out.push((a.0.max(b.0), a.1.min(b.1)));
            }
            if a.1 < b.1 {
                left += 1;
            } else {
                right += 1;
            }
        }
        Self(out)
    }
    pub(super) fn complement(self) -> Self {
        let mut out = Vec::new();
        let mut at = 0;
        for (lo, hi) in self.0 {
            if at < lo {
                out.push((at, lo - 1));
            }
            at = hi + 1;
        }
        if at <= 0x10ffff {
            out.push((at, 0x10ffff));
        }
        Self(out).intersect(Self::all())
    }
    pub(super) fn ascii(name: char) -> Option<Self> {
        let range = |a, b| Self::range(a, b, false);
        let value = match name.to_ascii_lowercase() {
            'd' => range('0', '9'),
            'w' => range('a', 'z')
                .union(range('A', 'Z'))
                .union(range('0', '9'))
                .union(Self::single('_', false)),
            's' => range('\t', '\r').union(Self::single(' ', false)),
            _ => return None,
        };
        Some(if name.is_ascii_uppercase() {
            value.complement()
        } else {
            value
        })
    }
    pub(super) fn posix(name: &str, insensitive: bool) -> Option<Self> {
        let range = |a, b| Self::range(a, b, insensitive);
        Some(match name {
            "Lower" => range('a', 'z'),
            "Upper" => range('A', 'Z'),
            "ASCII" => range('\0', '\u{7f}'),
            "Alpha" => range('a', 'z').union(range('A', 'Z')),
            "Digit" => range('0', '9'),
            "Alnum" => Self::posix("Alpha", insensitive)?.union(range('0', '9')),
            "Punct" => range('!', '/')
                .union(range(':', '@'))
                .union(range('[', '`'))
                .union(range('{', '~')),
            "Graph" => range('!', '~'),
            "Print" => range(' ', '~'),
            "Blank" => range(' ', ' ').union(range('\t', '\t')),
            "Cntrl" => range('\0', '\u{1f}').union(range('\u{7f}', '\u{7f}')),
            "XDigit" => range('0', '9')
                .union(range('a', 'f'))
                .union(range('A', 'F')),
            "Space" => Self::ascii('s')?,
            _ => return None,
        })
    }
}
