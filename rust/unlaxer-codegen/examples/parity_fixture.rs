//! Test fixture driver, not the UBNF CLI. Never invokes Java.
#[path = "../tests/support/mod.rs"]
mod support;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let fixture = args.next().ok_or("fixture required")?;
    let output = std::path::PathBuf::from(args.next().ok_or("output required")?);
    let ir = support::fixture(&fixture);
    std::fs::create_dir_all(&output)?;
    for file in unlaxer_codegen::generate(&ir)? {
        std::fs::write(output.join(file.relative_path), file.content)?;
    }
    Ok(())
}
