package org.unlaxer.dsl.codegen;

import org.unlaxer.parser.combinator.SyncPointRecoveryParser;
import org.unlaxer.parser.elementary.WordParser;

/** External token fixture: recovery is hidden behind a Simple token, not an annotation. */
public class ExternalRecoveryTokenParser extends SyncPointRecoveryParser {
    private static final long serialVersionUID = 1L;

    public ExternalRecoveryTokenParser() {
        super(new WordParser("ok"), ";");
    }
}
