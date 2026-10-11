//! Isolated, bounded correctness experiment, not a production facade.
use std::{
    fs,
    io::Read,
    path::Path,
    process::{Command, Stdio},
    time::{Duration, Instant},
};
mod canonical;
struct Request<'a> {
    source: &'a Path,
    text: String,
    grammar: &'a str,
    snapshot: &'a str,
    deadline: Instant,
    oracle: String,
    input_limit: usize,
}
fn read_bounded(path: &Path, limit: usize, exceeded: &'static str) -> Result<String, &'static str> {
    if !fs::metadata(path).map_err(|_| "IO")?.is_file() {
        return Err("REGULAR_FILE_REQUIRED");
    }
    let mut bytes = Vec::with_capacity(limit.min(4096));
    fs::File::open(path)
        .map_err(|_| "IO")?
        .take((limit + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|_| "IO")?;
    if bytes.len() > limit {
        return Err(exceeded);
    }
    String::from_utf8(bytes).map_err(|_| "UTF8")
}
fn invoke(executable: &str, r: &Request<'_>, attempt: &mut usize) -> Result<String, &'static str> {
    if Instant::now() >= r.deadline {
        return Err("DEADLINE");
    }
    if read_bounded(r.source, r.input_limit, "INPUT_LIMIT")? != r.text {
        return Err("SNAPSHOT_MISMATCH");
    }
    let output = r.source.with_extension(format!("reply-{}", *attempt + 1));
    let file = fs::File::create(&output).map_err(|_| "IO")?;
    let mut child = Command::new(executable)
        .args([r.source.to_str().ok_or("IO")?, r.grammar, r.snapshot])
        .stdout(Stdio::from(file))
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| "ENGINE_START")?;
    *attempt += 1;
    let status = loop {
        let oversized = fs::metadata(&output)
            .map(|m| m.len() > 65536)
            .unwrap_or(true);
        if Instant::now() >= r.deadline || oversized {
            let _ = child.kill();
            let _ = child.wait();
            let _ = fs::remove_file(&output);
            return Err(if oversized {
                "OUTPUT_LIMIT"
            } else {
                "DEADLINE"
            });
        }
        match child.try_wait() {
            Ok(Some(s)) => break s,
            Ok(None) => std::thread::sleep(Duration::from_millis(1)),
            Err(_) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err("ENGINE_WAIT");
            }
        }
    };
    if !status.success() {
        let _ = fs::remove_file(&output);
        return Err("ENGINE_ERROR");
    }
    if Instant::now() >= r.deadline {
        return Err("DEADLINE");
    }
    if read_bounded(r.source, r.input_limit, "INPUT_LIMIT")? != r.text {
        return Err("SNAPSHOT_MISMATCH");
    }
    if fs::metadata(&output)
        .map(|m| m.len() > 65536)
        .unwrap_or(true)
    {
        return Err("OUTPUT_LIMIT");
    }
    let response = read_bounded(&output, 65536, "OUTPUT_LIMIT");
    let _ = fs::remove_file(output);
    let response = response?;
    let mut lines = response.lines();
    let binding = format!(
        "SNAPSHOT\t{}\t{}\t{}",
        r.grammar,
        r.snapshot,
        canonical::hex(&r.text)
    );
    if lines.next() != Some(&binding) {
        return Err("BINDING_MISMATCH");
    }
    let body = lines.next().ok_or("PROTOCOL")?;
    if lines.next().is_some() || !(body.starts_with("ACCEPT\t") || body.starts_with("REJECT\t")) {
        return Err("PROTOCOL");
    }
    Ok(body.into())
}
fn verify(body: &str, oracle: &str) -> Result<(), &'static str> {
    if oracle.starts_with("ACCEPT\t") {
        if body != oracle {
            return Err("AST_OR_ACCEPTANCE_MISMATCH");
        }
    } else {
        let expected: Vec<_> = oracle.split('\t').collect();
        let actual: Vec<_> = body.split('\t').collect();
        if actual.len() != 5 || expected.len() != 2 || actual[..2] != expected[..] {
            return Err("REJECTION_MISMATCH");
        }
    }
    Ok(())
}
fn main() {
    let a: Vec<_> = std::env::args().collect();
    if a.len() != 10 {
        eprintln!("driver INPUT GRAMMAR_SHA SNAPSHOT PURE|UNKNOWN LEAN RICH ORACLE DEADLINE_MS INPUT_LIMIT");
        std::process::exit(2)
    }
    let (Ok(limit), Ok(ms)) = (a[9].parse::<usize>(), a[8].parse::<u64>()) else {
        eprintln!("invalid numeric argument: nonnegative DEADLINE_MS and INPUT_LIMIT required");
        std::process::exit(2);
    };
    let mut attempts = 0;
    let result = (|| -> Result<String, &'static str> {
        if limit > 4096 || ms > 30000 {
            return Err("INVALID_LIMIT");
        }
        let deadline = Instant::now() + Duration::from_millis(ms);
        let text = read_bounded(Path::new(&a[1]), limit, "INPUT_LIMIT")?;
        let r = Request {
            source: Path::new(&a[1]),
            text,
            grammar: &a[2],
            snapshot: &a[3],
            deadline,
            input_limit: limit,
            oracle: read_bounded(Path::new(&a[7]), 65536, "ORACLE_LIMIT")?
                .trim_end_matches('\n')
                .into(),
        };
        if a[4] != "PURE" {
            let body = invoke(&a[6], &r, &mut attempts)?;
            verify(&body, &r.oracle)?;
            return Ok(format!("RICH_ONCE\t{body}"));
        }
        let lean = invoke(&a[5], &r, &mut attempts)?;
        if lean.starts_with("ACCEPT\t") {
            verify(&lean, &r.oracle)?;
            return Ok(format!("LEAN\t{lean}"));
        }
        verify(&lean, &r.oracle)?;
        let rich = invoke(&a[6], &r, &mut attempts)?;
        if rich.starts_with("ACCEPT\t") {
            return Err("LEAN_REJECT_RICH_ACCEPT");
        }
        verify(&rich, &r.oracle)?;
        Ok(format!("RICH_FAILURE\t{rich}"))
    })();
    match result {
        Ok(body) => println!("OK\t{attempts}\t{body}"),
        Err(error) => println!("ERROR\t{attempts}\t{error}"),
    }
}
