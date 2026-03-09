package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;

public interface CodeGenerator {
    /** GrammarDecl から Java ソースコードを生成して返す */
    GeneratedSource generate(GrammarDecl grammar);

    record GeneratedSource(
        String packageName,
        String className,
        String source
    ) {}
}
