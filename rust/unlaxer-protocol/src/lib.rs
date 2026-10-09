//! Bounded Content-Length byte frames shared by stdio IDE protocols.
use std::io::{self, BufRead, Read, Write};
const MAX_MESSAGE: usize = 4 * 1024 * 1024;

/// Strict ASCII CRLF headers and a bounded byte body; protocols validate JSON themselves.
pub fn read_frame<R: BufRead>(input: &mut R) -> io::Result<Option<Vec<u8>>> {
    let mut length = None;
    let mut headers = 0;
    let mut count = 0;
    loop {
        let mut bytes = Vec::new();
        let read = input
            .take((8192 - count + 1) as u64)
            .read_until(b'\n', &mut bytes)?;
        if read == 0 && count == 0 {
            return Ok(None);
        }
        count += read;
        if count > 8192 || read == 0 || !bytes.ends_with(b"\r\n") || !bytes.is_ascii() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid protocol header",
            ));
        }
        if bytes == b"\r\n" {
            break;
        }
        headers += 1;
        if headers > 32 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "too many protocol headers",
            ));
        }
        let line = std::str::from_utf8(&bytes[..bytes.len() - 2]).unwrap();
        let (key, value) = line
            .split_once(':')
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid protocol header"))?;
        if key.eq_ignore_ascii_case("Content-Length") {
            let digits = value.trim();
            let parsed = if !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit()) {
                digits.parse::<usize>().ok().filter(|n| *n <= MAX_MESSAGE)
            } else {
                None
            };
            if length.is_some() || parsed.is_none() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "invalid Content-Length",
                ));
            }
            length = parsed;
        }
        if key.eq_ignore_ascii_case("Content-Type")
            && value.contains("charset=")
            && !value.ends_with("charset=utf-8")
            && !value.ends_with("charset=utf8")
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "unsupported charset",
            ));
        }
    }
    let size = length
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing Content-Length"))?;
    let mut body = vec![0; size];
    input.read_exact(&mut body)?;
    Ok(Some(body))
}

pub fn write_frame<W: Write>(output: &mut W, bytes: &[u8]) -> io::Result<()> {
    write!(output, "Content-Length: {}\r\n\r\n", bytes.len())?;
    output.write_all(bytes)?;
    output.flush()
}
