package org.unlaxer.dsl.ir;

/**
 * SPI for integrating parsers not generated from UBNF into the same IR pipeline.
 */
public interface ParserIrAdapter {
    ParserIrAdapterMetadata metadata();

    ParserIrDocument parseToIr(ParseRequest request);
}
