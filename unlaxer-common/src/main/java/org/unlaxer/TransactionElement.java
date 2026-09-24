package org.unlaxer;

import java.io.Serializable;
import java.util.Optional;
import java.util.IdentityHashMap;
import java.util.Map;
import org.unlaxer.context.TransactionalState;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.ChoiceInterface;
import org.unlaxer.parser.combinator.NonOrdered;

import org.unlaxer.Cursor.EndExclusiveCursor;
import org.unlaxer.Source.SourceKind;



public class TransactionElement implements Serializable{
	
	private static final long serialVersionUID = -4168699143819523755L;

	Optional<TokenKind> tokenKind;
	ParserCursor parserCursor ;
	
	boolean resetMatchedWithConsumed = true;
	
	public final TokenList tokens = new TokenList();

    private transient Map<TransactionalState, Runnable> stateCheckpoints;
    private transient long savedMemoizationStateVersion;
    private transient boolean hasSavedMemoizationStateVersion;

    /*
     * Choice/interleave metadata is observable parser state too.  Keep a small undo journal
     * instead of cloning the complete ParseContext maps at every parser transaction.
     */
    private transient Map<ChoiceInterface, Parser> previousChosenParsers;
    private transient Map<NonOrdered, Parsers> previousOrderedParsers;

    /** Internal transaction hook: capture each explicitly registered owner once. */
    public void checkpointState(TransactionalState state) {
        checkpointStateIfAbsent(state);
    }

    /** Internal transaction hook that reports whether it captured a new snapshot. */
    public boolean checkpointStateIfAbsent(TransactionalState state) {
        // A transaction holds a handful of entries at most, and one is opened per parser call.
        if (stateCheckpoints == null) stateCheckpoints = new IdentityHashMap<>(4);
        if (stateCheckpoints.containsKey(state)) return false;
        stateCheckpoints.put(state, state.checkpoint());
        return true;
    }

    public boolean hasStateCheckpoints() {
        return stateCheckpoints != null && false == stateCheckpoints.isEmpty();
    }

    public void saveMemoizationStateVersion(long version) {
        if (hasSavedMemoizationStateVersion) {
            throw new IllegalStateException("transaction state version snapshot already exists");
        }
        savedMemoizationStateVersion = version;
        hasSavedMemoizationStateVersion = true;
    }

    public long takeMemoizationStateVersion() {
        if (false == hasSavedMemoizationStateVersion) {
            throw new IllegalStateException("transaction state version snapshot is missing");
        }
        hasSavedMemoizationStateVersion = false;
        return savedMemoizationStateVersion;
    }

    /** Internal transaction hook, run after rollback listeners have been notified. */
    public void restoreState() {
        if (stateCheckpoints != null) stateCheckpoints.values().forEach(Runnable::run);
    }

    public void recordChosenParser(ChoiceInterface choice, Parser previous) {
        if (previousChosenParsers == null) previousChosenParsers = new IdentityHashMap<>(4);
        if (!previousChosenParsers.containsKey(choice)) previousChosenParsers.put(choice, previous);
    }

    public void recordOrderedParsers(NonOrdered nonOrdered, Parsers previous) {
        if (previousOrderedParsers == null) previousOrderedParsers = new IdentityHashMap<>(4);
        if (!previousOrderedParsers.containsKey(nonOrdered)) previousOrderedParsers.put(nonOrdered, previous);
    }

    /** Propagates the earliest undo value into the enclosing transaction. */
    public void absorbSelectionChanges(TransactionElement child) {
        if (child.previousChosenParsers != null) {
            child.previousChosenParsers.forEach(this::recordChosenParser);
        }
        if (child.previousOrderedParsers != null) {
            child.previousOrderedParsers.forEach(this::recordOrderedParsers);
        }
    }

    /** Snapshot only selections committed inside this transaction, for safe success replay. */
    public Map<ChoiceInterface, Parser> snapshotChosenParsers(Map<ChoiceInterface, Parser> chosenParsers) {
        if (previousChosenParsers == null) return Map.of();
        Map<ChoiceInterface, Parser> snapshot = new IdentityHashMap<>(4);
        for (ChoiceInterface choice : previousChosenParsers.keySet()) {
            snapshot.put(choice, chosenParsers.get(choice));
        }
        return snapshot;
    }

    /** Restores choice/interleave observations made by this transaction and its commits. */
    public void restoreSelectionChanges(
            Map<ChoiceInterface, Parser> chosenParsers,
            Map<NonOrdered, Parsers> orderedParsers) {
        if (previousChosenParsers != null) {
            previousChosenParsers.forEach((choice, previous) -> {
                if (previous == null) chosenParsers.remove(choice);
                else chosenParsers.put(choice, previous);
            });
        }
        if (previousOrderedParsers != null) {
            previousOrderedParsers.forEach((nonOrdered, previous) -> {
                if (previous == null) orderedParsers.remove(nonOrdered);
                else orderedParsers.put(nonOrdered, previous);
            });
        }
    }
	
	public TransactionElement(ParserCursor parserCursor) {
		super();
		this.parserCursor = new ParserCursor(parserCursor,true);
		tokenKind = Optional.empty();
	}
	
	
	public TransactionElement(ParserCursor cursor, boolean resetMatchedWithConsumed) {
		super();
		this.parserCursor = cursor;
		this.resetMatchedWithConsumed = resetMatchedWithConsumed;
	}


	public TransactionElement createNew() {
		return new TransactionElement(new ParserCursor(parserCursor,resetMatchedWithConsumed),resetMatchedWithConsumed);
	}
	
	public void consume(CodePointLength length){
		parserCursor.addPosition(length.toOffset());
	}
	
	public void matchOnly(CodePointLength length){
		parserCursor.addMatchedPosition(length.toOffset());
	}
	
	public void addToken(Token token){
		addToken(token,TokenKind.consumed);
	}
	
	public void addToken(Token token ,TokenKind tokenKind){
		tokens.add(token);
		this.tokenKind = Optional.of(tokenKind);
	}
	
	public Source source(){
	  return tokens.toSource(SourceKind.detached);
	}
	
	public CodePointIndex getPosition(TokenKind tokenKind){
		return parserCursor.getPosition(tokenKind);
	}
	
	public EndExclusiveCursor getCursor(TokenKind tokenKind){
	   return parserCursor.getCursor(tokenKind);
  }
	
	public TokenList getTokens(){
		return tokens;
	}

	public Optional<TokenKind> getTokenKind() {
		return tokenKind;
	}

	public ParserCursor getParserCursor() {
		return parserCursor;
	}

	public void setCursor(ParserCursor cursor) {
		this.parserCursor = cursor;
	}
	
	public void setResetMatchedWithConsumed(boolean resetMatchedWithConsumed) {
		this.resetMatchedWithConsumed = resetMatchedWithConsumed;
	}
}
