//! Unix user.home for explicit HTTPS resolution only (JDK 17/21, #958).
use std::ffi::CStr;
use std::os::unix::ffi::OsStrExt;
use std::path::PathBuf;

// The only unsafe boundary in the generator: libc owns the passwd ABI and writes
// pointers into the supplied buffer. No subprocess, environment mutation or
// credentials-file access occurs here.
#[allow(unsafe_code)]
pub(super) fn get() -> PathBuf {
    let mut size = 1024;
    loop {
        let mut buffer = vec![0u8; size];
        let mut entry = std::mem::MaybeUninit::<libc::passwd>::uninit();
        let mut result = std::ptr::null_mut();
        // SAFETY: getuid has no preconditions. entry, buffer and result are valid,
        // disjoint writable allocations which outlive the call. getpwuid_r writes
        // at most buffer.len() bytes and initializes entry on successful lookup.
        let status = unsafe {
            libc::getpwuid_r(
                libc::getuid(),
                entry.as_mut_ptr(),
                buffer.as_mut_ptr().cast(),
                buffer.len(),
                &mut result,
            )
        };
        if status == libc::ERANGE && size < 1024 * 1024 {
            size *= 2;
            continue;
        }
        if status == 0 && !result.is_null() {
            // SAFETY: successful getpwuid_r initialized entry; pw_dir is either
            // null or a NUL-terminated string valid while buffer is alive. Copy
            // its bytes into the owned path before dropping that storage.
            let entry = unsafe { entry.assume_init() };
            if !entry.pw_dir.is_null() {
                let home = unsafe { CStr::from_ptr(entry.pw_dir) }.to_bytes();
                if !home.is_empty() {
                    return PathBuf::from(std::ffi::OsStr::from_bytes(home));
                }
            }
        }
        // JDK 21 falls back to nonempty HOME, then "?". JDK 17 differs
        // on missing/empty passwd entries; keep that measured limit explicit.
        return std::env::var_os("HOME")
            .filter(|home| !home.is_empty())
            .map_or_else(|| PathBuf::from("?"), PathBuf::from);
    }
}
