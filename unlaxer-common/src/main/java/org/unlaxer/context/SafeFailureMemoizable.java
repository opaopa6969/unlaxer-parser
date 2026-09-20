package org.unlaxer.context;

/**
 * Marker emitted on a generated parser class whose complete dependency closure is known to be
 * independent of mutable parse state. Runtime checks require this interface on the parser's exact
 * class; subclasses do not inherit safety merely by extending a marked class.
 */
public interface SafeFailureMemoizable {}
