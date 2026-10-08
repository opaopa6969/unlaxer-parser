package org.unlaxer.parser.combinator;

import java.util.Set;

import org.unlaxer.CodePointLength;
import org.unlaxer.Committed;
import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.Source;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
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
	/** Whether to consume the sync token or stop before a following construct. */
	public enum Mode { SYNC, BEFORE_SYNC, SKIP }

	private static final long serialVersionUID = 1L;

	private final Set<String> syncTokens;
	private final RecoveryDiagnostic.Marker errorMarker;
	private final Mode mode;
	private static final String DEFAULT_MESSAGE = "syntax error: skipped to sync point";

	/**
	 * Creates a recovery parser with the given sync tokens and default error message.
	 *
	 * @param child      the parser to wrap
	 * @param syncTokens tokens that serve as synchronization points (e.g., ";", "}")
	 */
	public SyncPointRecoveryParser(Parser child, String... syncTokens) {
		this(child, Mode.SYNC, DEFAULT_MESSAGE, syncTokens);
	}

	/**
	 * Creates a recovery parser with a Name, sync tokens, and default error message.
	 */
	public SyncPointRecoveryParser(Name name, Parser child, String... syncTokens) {
		super(name, child);
		this.mode = Mode.SYNC;
		this.syncTokens = checkedTokens(Mode.SYNC, syncTokens);
		this.errorMarker = new RecoveryDiagnostic.Marker(DEFAULT_MESSAGE);
	}

	/**
	 * Private constructor for custom error message (used by static factory).
	 */
	public SyncPointRecoveryParser(Parser child, Mode mode, String... syncTokens) {
		this(child, mode, DEFAULT_MESSAGE, syncTokens);
	}

	private SyncPointRecoveryParser(Parser child, Mode mode, String errorMessage, String... syncTokens) {
		super(child);
		this.mode = java.util.Objects.requireNonNull(mode, "mode");
		this.syncTokens = checkedTokens(mode, syncTokens);
		this.errorMarker = new RecoveryDiagnostic.Marker(errorMessage);
	}

	private static Set<String> checkedTokens(Mode mode, String[] tokens) {
		Set<String> result = Set.of(tokens);
		if (result.stream().anyMatch(String::isEmpty)) {
			throw new IllegalArgumentException("recovery sync token must not be empty");
		}
		return result;
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
		return new SyncPointRecoveryParser(child, Mode.SYNC, errorMessage, syncTokens);
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

		// Scan from the active cursor. Match-only lookahead must not consume input.
		Source remain = parseContext.getRemain(tokenKind);
		String remainStr = remain.toString();

		if (remainStr.isEmpty()) {
			// Nothing left to scan — fail
			parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
			return Parsed.FAILED;
		}

		// Find the nearest sync point in the remaining input
		int nearestSyncPos = findNearestSyncPoint(remainStr);
		int skipUtf16;
		if (mode == Mode.SYNC) {
			if (nearestSyncPos < 0) {
				parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
				return Parsed.FAILED;
			}
			skipUtf16 = nearestSyncPos + findSyncTokenAt(remainStr, nearestSyncPos).length();
		} else if (mode == Mode.BEFORE_SYNC) {
			if (nearestSyncPos <= 0) {
				parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
				return Parsed.FAILED;
			}
			skipUtf16 = nearestSyncPos;
		} else if (syncTokens.isEmpty()) {
			skipUtf16 = Character.charCount(remainStr.codePointAt(0));
		} else if (nearestSyncPos < 0) {
			skipUtf16 = remainStr.length();
		} else if (nearestSyncPos == 0) {
			skipUtf16 = Character.charCount(remainStr.codePointAt(0));
		} else {
			skipUtf16 = nearestSyncPos;
		}
		CodePointLength skipCodePointLength = new CodePointLength(remainStr.codePointCount(0, skipUtf16));

		// Begin a new transaction for the recovery region
		parseContext.begin(this);

		if (tokenKind.isMatchOnly()) {
			// Lookahead may succeed, but it must not publish semantic recovery state.
			parseContext.matchOnly(skipCodePointLength);
		} else {
			// A full-span marker is both the CST error and the public recovery diagnostic.
			Source skippedSource = parseContext.peek(tokenKind, skipCodePointLength);
			parseContext.getCurrent().addToken(new Token(tokenKind, skippedSource, errorMarker), tokenKind);
			parseContext.consume(skipCodePointLength);
		}

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
		String longest = "";
		for (String syncToken : syncTokens) {
			if (source.startsWith(syncToken, pos) && syncToken.length() > longest.length()) {
				longest = syncToken;
			}
		}
		return longest;
	}

	/**
	 * Returns the set of sync tokens configured for this parser.
	 */
	public Set<String> getSyncTokens() {
		return syncTokens;
	}
}
