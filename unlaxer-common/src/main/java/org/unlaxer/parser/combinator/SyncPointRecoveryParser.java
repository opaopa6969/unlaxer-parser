package org.unlaxer.parser.combinator;

import java.util.Set;

import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.Committed;
import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.Source;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.ErrorMessageParser;
import org.unlaxer.parser.Parser;

/**
 * Error recovery parser that skips input to the next sync point on parse failure.
 *
 * <p>When the wrapped parser fails, this parser:
 * <ol>
 *   <li>Records the error position</li>
 *   <li>Scans forward to the next occurrence of any sync token (e.g., ';', '}')</li>
 *   <li>Creates an error token covering the skipped region</li>
 *   <li>Returns success so parsing can continue</li>
 * </ol>
 *
 * <p>This implements the sync-point recovery strategy described in DGE future work (G1, G3, G5).
 * Sync points are configurable tokens where parsing can resume after an error.
 *
 * <p>Usage example:
 * <pre>
 *   new SyncPointRecoveryParser(statementParser, ";", "}")
 * </pre>
 */
public class SyncPointRecoveryParser extends ConstructedSingleChildParser {

	private static final long serialVersionUID = 1L;

	private final Set<String> syncTokens;
	private final ErrorMessageParser errorMarker;

	/**
	 * Creates a recovery parser with the given sync tokens and default error message.
	 *
	 * @param child      the parser to wrap
	 * @param syncTokens tokens that serve as synchronization points (e.g., ";", "}")
	 */
	public SyncPointRecoveryParser(Parser child, String... syncTokens) {
		super(child);
		this.syncTokens = Set.of(syncTokens);
		this.errorMarker = new ErrorMessageParser("syntax error: skipped to sync point");
	}

	/**
	 * Creates a recovery parser with a Name, sync tokens, and default error message.
	 */
	public SyncPointRecoveryParser(Name name, Parser child, String... syncTokens) {
		super(name, child);
		this.syncTokens = Set.of(syncTokens);
		this.errorMarker = new ErrorMessageParser("syntax error: skipped to sync point");
	}

	/**
	 * Private constructor for custom error message (used by static factory).
	 */
	private SyncPointRecoveryParser(Parser child, Set<String> syncTokens, String errorMessage) {
		super(child);
		this.syncTokens = syncTokens;
		this.errorMarker = new ErrorMessageParser(errorMessage);
	}

	/**
	 * Creates a recovery parser with a custom error message.
	 *
	 * @param child        the parser to wrap
	 * @param errorMessage custom error message for skipped regions
	 * @param syncTokens   tokens that serve as synchronization points
	 * @return a new SyncPointRecoveryParser
	 */
	public static SyncPointRecoveryParser withMessage(Parser child, String errorMessage, String... syncTokens) {
		return new SyncPointRecoveryParser(child, Set.of(syncTokens), errorMessage);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);

		parseContext.begin(this);

		Parsed result = getChild().parse(parseContext, tokenKind, invertMatch);

		if (result.isSucceeded()) {
			// Child succeeded normally — commit and return
			Committed committed = parseContext.commit(this, tokenKind);
			Parsed succeededParsed = new Parsed(committed);
			parseContext.endParse(this, succeededParsed, parseContext, tokenKind, invertMatch);
			return succeededParsed;
		}

		// Child failed — attempt recovery by scanning to next sync point
		parseContext.rollback(this);

		// Get remaining source from current consumed position
		Source remain = parseContext.getRemain(TokenKind.consumed);
		String remainStr = remain.toString();

		if (remainStr.isEmpty()) {
			// Nothing left to scan — fail
			parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
			return Parsed.FAILED;
		}

		// Find the nearest sync point in the remaining input
		int nearestSyncPos = findNearestSyncPoint(remainStr);

		if (nearestSyncPos < 0) {
			// No sync point found — fail entirely
			parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
			return Parsed.FAILED;
		}

		// Calculate skip length: include the sync token itself
		String syncToken = findSyncTokenAt(remainStr, nearestSyncPos);
		int skipLength = nearestSyncPos + syncToken.length();
		CodePointLength skipCodePointLength = new CodePointLength(skipLength);

		// Begin a new transaction for the recovery region
		parseContext.begin(this);

		// Place the error marker token at the current position
		// This embeds the error message in the parse tree
		errorMarker.parse(parseContext, tokenKind, invertMatch);

		// Get the source covering the skipped region before consuming
		Source skippedSource = parseContext.peek(TokenKind.consumed, skipCodePointLength);

		// Create a token that covers the skipped region, associated with this parser
		Token skippedToken = new Token(tokenKind, skippedSource, this);
		parseContext.getCurrent().addToken(skippedToken, tokenKind);

		// Advance the cursor past the skipped region
		parseContext.consume(skipCodePointLength);

		// Commit the recovery
		Committed committed = parseContext.commit(this, tokenKind);
		Parsed recoveredParsed = new Parsed(committed);
		parseContext.endParse(this, recoveredParsed, parseContext, tokenKind, invertMatch);
		return recoveredParsed;
	}

	/**
	 * Finds the index of the nearest sync point in the given string.
	 *
	 * @param source the remaining source string to scan
	 * @return index of the nearest sync point, or -1 if none found
	 */
	int findNearestSyncPoint(String source) {
		int nearest = -1;
		for (String syncToken : syncTokens) {
			int idx = source.indexOf(syncToken);
			if (idx >= 0 && (nearest < 0 || idx < nearest)) {
				nearest = idx;
			}
		}
		return nearest;
	}

	/**
	 * Finds which sync token occurs at the given position.
	 *
	 * @param source the source string
	 * @param pos    the position to check
	 * @return the sync token at the position
	 */
	String findSyncTokenAt(String source, int pos) {
		for (String syncToken : syncTokens) {
			if (source.startsWith(syncToken, pos)) {
				return syncToken;
			}
		}
		// Should not happen if called after findNearestSyncPoint
		return "";
	}

	/**
	 * Returns the set of sync tokens configured for this parser.
	 */
	public Set<String> getSyncTokens() {
		return syncTokens;
	}
}
