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

    /**
     * Returns the {@code @Generated} annotation string to prepend to generated source files.
     *
     * <p>Uses {@code javax.annotation.processing.Generated} (available in Java 9+)
     * so no extra dependency is required. The value is the fully-qualified name of
     * the generator class.</p>
     *
     * @param generatorClass fully-qualified class name of the generator
     * @return annotation line ending with {@code \n}
     */
    static String generatedAnnotation(String generatorClass) {
        return "@javax.annotation.processing.Generated(\"" + generatorClass + "\")\n";
    }
}
