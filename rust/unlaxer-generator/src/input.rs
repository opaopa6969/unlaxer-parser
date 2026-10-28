//! Grammar-file failures retain their category until the CLI selects an exit status.
use std::fmt;
use std::io;
use std::path::Path;

#[derive(Debug)]
pub enum FileError {
    Io(String),
    Grammar(String),
}

impl FileError {
    pub fn exit_code(&self) -> u8 {
        match self {
            Self::Io(_) => 4,
            Self::Grammar(_) => 3,
        }
    }

    pub(crate) fn context(self, context: &str) -> Self {
        match self {
            Self::Io(message) => Self::Io(format!("{context}: {message}")),
            Self::Grammar(message) => Self::Grammar(format!("{context}: {message}")),
        }
    }
}

impl fmt::Display for FileError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(message) | Self::Grammar(message) => f.write_str(message),
        }
    }
}

impl std::error::Error for FileError {}

impl From<String> for FileError {
    fn from(message: String) -> Self {
        Self::Grammar(message)
    }
}

/// Read an already absolute, lexically normalized entry grammar (Java Files.readString).
pub fn read_grammar(path: &Path) -> Result<String, FileError> {
    let bytes = std::fs::read(path).map_err(|error| FileError::Io(io_message(path, error)))?;
    String::from_utf8(bytes).map_err(|error| FileError::Io(utf8_message(error, true)))
}

/// Match Java NIO's file exception messages, without Rust's numeric errno suffix.
pub(crate) fn io_message(path: &Path, error: io::Error) -> String {
    match error.kind() {
        io::ErrorKind::NotFound | io::ErrorKind::PermissionDenied => path.display().to_string(),
        io::ErrorKind::IsADirectory => "Is a directory".into(),
        // Non-OS reader errors already carry their content diagnostic.
        io::ErrorKind::InvalidData => error.to_string(),
        _ => {
            #[cfg(unix)]
            if error.raw_os_error() == Some(libc::ELOOP) {
                return format!("{}: Too many levels of symbolic links or unable to access attributes of symbolic link", path.display());
            }
            let message = error.to_string();
            let reason = error
                .raw_os_error()
                .and_then(|errno| message.strip_suffix(&format!(" (os error {errno})")))
                .unwrap_or(&message);
            format!("{}: {reason}", path.display())
        }
    }
}

/// Java's Files.readString and CharsetDecoder have different malformed lengths.
/// Only failure formatting inspects the invalid suffix; valid input is decoded once.
pub(crate) fn utf8_message(error: std::string::FromUtf8Error, read_string: bool) -> String {
    let invalid = &error.as_bytes()[error.utf8_error().valid_up_to()..];
    let length = if read_string {
        match invalid[0] {
            0xe0..=0xef if invalid.len() >= 3 => 3,
            0xe0..=0xef
                if invalid.len() == 2
                    && (invalid[1] & 0xc0 != 0x80 || invalid[0] == 0xe0 && invalid[1] < 0xa0) =>
            {
                2
            }
            0xf0..=0xf7 if invalid.len() >= 4 => 4,
            _ => 1,
        }
    } else if invalid[0] == 0xed && invalid.get(1).is_some_and(|b| (0xa0..=0xbf).contains(b)) {
        // CharsetDecoder consumes a surrogate encoding as one malformed sequence.
        if invalid.get(2).is_some_and(|b| b & 0xc0 == 0x80) {
            3
        } else {
            2
        }
    } else {
        error.utf8_error().error_len().unwrap_or(invalid.len())
    };
    format!("Input length = {length}")
}
