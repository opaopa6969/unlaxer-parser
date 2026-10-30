fn main() {
    let status = unlaxer_ubnf_lsp::Server::default()
        .serve(&mut std::io::stdin().lock(), &mut std::io::stdout().lock())
        .unwrap_or_else(|error| {
            eprintln!("UBNF LSP: {error}");
            1
        });
    std::process::exit(status);
}
