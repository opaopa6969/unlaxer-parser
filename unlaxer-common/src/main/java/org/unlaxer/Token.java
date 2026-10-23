package org.unlaxer;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.unlaxer.Cursor.EndExclusiveCursor;
import org.unlaxer.ParserHierarchy.NameKind;
import org.unlaxer.Source.SourceKind;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;
import org.unlaxer.util.FactoryBoundCache;
import org.unlaxer.util.NullSafetyConcurrentHashMap;

public class Token implements Serializable{
	
	private static final long serialVersionUID = -2232289508694932061L;

//	static final FactoryBoundCache<Tup EndExclusiveCursor, Source> empties = 
//			new FactoryBoundCache<>(cursor->StringSource.createSubSource("", null,  
//			    new CodePointOffset(cursor.getPosition())));
	
	static final FactoryBoundCache<Token, String> displayString = 
			new FactoryBoundCache<>(
					token->TokenPrinter.get(token,0,OutputLevel.detail,false));
	
	public final Source source;
	@Deprecated
	public final Optional<String> tokenString;
	@Deprecated
	public final Range tokenRange;
	public Parser parser;
	
	public Optional<Token> parent;
	private final TokenList originalChildren;
	//TODO make private and rename astNodeChildren
	public  final TokenList filteredChildren; // astNodeChildren
	
	/*
	 * Side maps stay empty for almost every token, but a parse allocates one token per committed
	 * rule (888k of them for a 20 KB input), so the two maps and their null sentinels were 11% of
	 * a parse's allocation and stayed reachable for as long as the tree. They are created on the
	 * first write instead; an absent map reads exactly like an empty one.
	 */
	private volatile Map<Name,Object> extraObjectByName;
	private volatile Map<Name,Token> relatedTokenByName;

	public enum ChildrenKind{
		original,
		astNodes
	}
	
	public final TokenKind tokenKind;
	
	
	public Token(TokenKind tokenKind , Source token, Parser parser) {
		this(tokenKind , token , parser , new TokenList());
	}
	
	@Deprecated
	public Token(TokenKind tokenKind , List<? extends Token> tokens , Parser parser) {
		this(tokenKind, TokenList.of(new ArrayList<>(tokens)), parser);
	}
	
	@Deprecated
	public Token(TokenKind tokenKind , List<? extends Token> tokens , Parser parser , int position) {
		this(tokenKind, TokenList.of(new ArrayList<>(tokens)), parser);
	}
	
	@Deprecated
	public Token(TokenKind tokenKind , Source token, Parser parser , int position) {
		this(tokenKind, token, parser);
	}
	
	public Token(TokenKind tokenKind , TokenList tokens , Parser parser) {
		this(tokenKind , 
			tokens.toSource(SourceKind.subSource),
			parser,
			tokens);
	}
	
	// TODO too specialized...?
//	Predicate<Token> AST_NODES = token -> token.parser.hasTag(NodeKind.node.getTag()); <-こちらだと動かない。そもそもTagなしの状態もありってことかな？
  Predicate<Token> AST_NODES = token -> false == token.parser.hasTag(NodeKind.notNode.getTag());

	
	public Token(TokenKind tokenKind , Source token, Parser parser , TokenList children) {
		super();
		this.tokenKind = tokenKind;
		this.source = token;
		this.tokenString = token == null ? Optional.empty() : token.nonEmptyString();
		// sourceRange() is cursorRange().toRange() without building the CursorRange (perf #276).
		this.tokenRange = token == null ? Range.invalidRange() : token.sourceRange();
		this.parser = parser;
		this.originalChildren = children;
		parent= Optional.empty();
		// Runs on every commit: set parents and filter AST children with indexed loops instead
		// of two Stream pipelines and an intermediate list.
		int size = children.size();
		TokenList astChildren = new TokenList(size);
		Optional<Token> self = size == 0 ? null : Optional.of(this);
		for (int i = 0; i < size; i++) {
			Token child = children.get(i);
			child.parent = self;
//			child.parser.setParent(parser);
			if (AST_NODES.test(child)) astChildren.add(child);
		}
		this.filteredChildren = astChildren;
	}
	
	/**
	 * Returns a deep copy of this token sub-tree with fresh {@link Token} instances. Because
	 * {@link #parent} is mutable and is rewritten whenever a token is placed under a new parent,
	 * a cached token sub-tree must be copied before being spliced into another parse tree (e.g.
	 * packrat success memoization, issue #40) — otherwise replaying it would re-parent the shared
	 * instances and corrupt the original tree. {@link Source} is immutable and is shared; the
	 * {@code extraObjectByName}/{@code relatedTokenByName} side-maps are copied shallowly.
	 */
	public Token deepCopy() {
		Token copy;
		if (originalChildren.isEmpty()) {
			copy = new Token(tokenKind, source, parser);
		} else {
			TokenList copiedChildren = new TokenList();
			for (Token child : originalChildren) {
				copiedChildren.add(child.deepCopy());
			}
			copy = new Token(tokenKind, source, parser, copiedChildren);
		}
		copy.copySideMapsFrom(this);
		return copy;
	}

    /**
     * Internal memo snapshot plan. Traversal and pending frames are bounded by the structural
     * charge limit (not an RSS estimate); unsupported or malformed public trees return null rather
     * than entering recursive/overridable deepCopy. The caller must own the tree and not mutate it
     * concurrently.
     */
    public static MemoSnapshot prepareMemoSnapshot(TokenList roots, long limit) {
        return prepareMemoSnapshot(roots, null, limit);
    }

    /**
     * Also retains the tokens of a Parsed that a parser returns instead of its committed token
     * (#744): a Choice returns its selected child's Parsed, whose root may be filtered out of the
     * committed CST. A returned token that is in {@code roots} shares that copy; any other is
     * copied as a hidden sub-tree, which {@link MemoSnapshot#replay()} returns but does not add
     * to {@code roots}. A hidden sub-tree that overlaps a copied token fails closed (null).
     */
    public static MemoSnapshot prepareMemoSnapshot(TokenList roots, List<Token> returned, long limit) {
        int returnedCount = returned == null ? 0 : returned.size();
        if (roots == null
                || roots.getClass() != TokenList.class
                || limit < 64
                || limit > 64L * 1024 * 1024
                || roots.size() > (limit - 64) / 16) return null;
        // Returned ids are uncharged, so success memo capacity stays as before; a hidden
        // sub-tree is charged per copied node like the CST.
        long bytes = 64 + 16L * roots.size();
        var nodes = new ArrayList<MemoNode>();
        var ids = new java.util.IdentityHashMap<Token, Integer>();
        var sources = new java.util.IdentityHashMap<Source, Boolean>();
        var frames = new ArrayDeque<MemoFrame>();
        int[] rootIds = new int[roots.size()];
        int[] returnedIds = returnedCount == 0 ? NO_MEMO_IDS : new int[returnedCount];
        int nextReturned = 0;
        frames.push(new MemoFrame(roots, -1));
        while (true) {
            if (frames.isEmpty()) {
                // Hidden sub-trees follow the CST, so a returned CST token reuses its copy.
                while (nextReturned < returnedCount && ids.containsKey(returned.get(nextReturned))) {
                    returnedIds[nextReturned] = ids.get(returned.get(nextReturned));
                    nextReturned++;
                }
                if (nextReturned == returnedCount) break;
                TokenList hidden = new TokenList(1);
                hidden.add(returned.get(nextReturned));
                frames.push(new MemoFrame(hidden, HIDDEN_MEMO_ROOT, nextReturned++));
            }
            MemoFrame frame = frames.peek();
            if (frame.next == frame.children.size()) {
                frames.pop();
                continue;
            }
            int slot = frame.next++;
            Token token = frame.children.get(slot);
            if (token == null
                    || token.getClass() != Token.class
                    || ids.containsKey(token)
                    || token.parser == null
                    || token.tokenKind == null
                    || !memoSourceSafe(token.source, sources, (int) (limit / 128))
                    || token.originalChildren == null
                    || token.originalChildren.getClass() != TokenList.class
                    || token.filteredChildren == null
                    || token.filteredChildren.getClass() != TokenList.class) return null;
            int children = token.originalChildren.size(), filtered = token.filteredChildren.size();
            int extras = token.extraObjectByName == null ? 0 : token.extraObjectByName.size();
            int related = token.relatedTokenByName == null ? 0 : token.relatedTokenByName.size();
            long charge =
                    512L
                            + 8L * token.tokenString.orElse("").length()
                            + 24L * children
                            + 16L * filtered
                            + (extras == 0 ? 0 : 128L + 128L * extras)
                            + (related == 0 ? 0 : 128L + 128L * related);
            if (charge > limit - bytes || filtered > children) return null;
            Object[] extraValues = memoMapSnapshot(token.extraObjectByName, extras);
            Object[] relatedValues = memoMapSnapshot(token.relatedTokenByName, related);
            if (extraValues == null || relatedValues == null) return null;
            bytes += charge;
            int index = nodes.size();
            ids.put(token, index);
            nodes.add(
                    new MemoNode(
                            token, frame.parent, children, filtered, extraValues, relatedValues));
            if (frame.parent == HIDDEN_MEMO_ROOT) returnedIds[frame.returnedSlot] = index;
            else if (frame.parent < 0) rootIds[slot] = index;
            else nodes.get(frame.parent).children[slot] = index;
            if (children != 0) frames.push(new MemoFrame(token.originalChildren, index));
        }
        boolean[] filteredSeen = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            MemoNode node = nodes.get(i);
            for (int j = 0; j < node.filtered.length; j++) {
                Integer child = ids.get(node.token.filteredChildren.get(j));
                if (child == null || nodes.get(child).parent != i || filteredSeen[child])
                    return null;
                filteredSeen[child] = true;
                node.filtered[j] = child;
            }
        }
        return new MemoSnapshot(nodes, rootIds, returnedIds, bytes);
    }

    private static final int HIDDEN_MEMO_ROOT = -2;
    private static final int[] NO_MEMO_IDS = new int[0];

    private static boolean memoSourceSafe(
            Source source, java.util.IdentityHashMap<Source, Boolean> seen, int maximum) {
        if (source == null) return false;
        if (seen.containsKey(source)) return true;
        var pending = new ArrayDeque<Source>();
        pending.push(source);
        while (!pending.isEmpty()) {
            Source next = pending.pop();
            if (seen.containsKey(next)) continue;
            if (next.getClass() != StringSource.class && next.getClass() != RangedString.class
                    || seen.size() >= maximum) return false;
            seen.put(next, Boolean.TRUE);
            // Exact built-ins only: no custom Source callback. Do not traverse text/CP arrays.
            Source root = next.root();
            if (root == null) return false;
            pending.push(root);
            next.parent().ifPresent(pending::push);
        }
        return true;
    }

    private static final Object[] NO_MEMO_METADATA = new Object[0];

    private static Object[] memoMapSnapshot(Map<Name, ?> map, int expected) {
        if (map == null || expected == 0 && map.isEmpty()) return NO_MEMO_METADATA;
        Object[] result = new Object[expected * 2];
        int count = 0;
        for (var entry : map.entrySet()) {
            Name name = entry.getKey();
            if (count >= expected || name == null || name.getClass() != Name.class) return null;
            result[count * 2] = name;
            result[count * 2 + 1] = entry.getValue();
            count++;
        }
        return count == expected ? result : null;
    }

    private static final class MemoFrame {
        final TokenList children;
        final int parent;
        final int returnedSlot;
        int next;

        MemoFrame(TokenList children, int parent) {
            this(children, parent, -1);
        }

        MemoFrame(TokenList children, int parent, int returnedSlot) {
            this.children = children;
            this.parent = parent;
            this.returnedSlot = returnedSlot;
        }
    }

    private static final class MemoNode {
        Token token;
        final int parent;
        final int[] children, filtered;
        final Object[] extras, related;
        final Parser parser;

        MemoNode(
                Token token,
                int parent,
                int children,
                int filtered,
                Object[] extras,
                Object[] related) {
            this.token = token;
            this.parent = parent;
            this.parser = token.parser;
            this.children = new int[children];
            this.filtered = new int[filtered];
            this.extras = extras;
            this.related = related;
        }
    }

    /**
     * Opaque, memo-owned topology. Sources/host metadata values retain their historical shallow
     * contract.
     */
    public static final class MemoSnapshot {
        private final List<MemoNode> nodes;
        private final int[] roots;
        private final int[] returned;
        private final long bytes;

        private MemoSnapshot(List<MemoNode> nodes, int[] roots, int[] returned, long bytes) {
            this.nodes = nodes;
            this.roots = roots;
            this.returned = returned;
            this.bytes = bytes;
        }

        public long retainedBytes() {
            return bytes;
        }

        /** First copy releases all original token references from the retained plan. */
        public TokenList freeze() {
            Token[] copies = copies();
            for (int i = 0; i < copies.length; i++) nodes.get(i).token = copies[i];
            return rootList(copies);
        }

        /** Replay never calls public deepCopy or source/parser callbacks, even for deep trees. */
        public TokenList copy() {
            Token[] copies = copies();
            return rootList(copies);
        }

        /** One replay copy: the roots and, in preparation order, the returned tokens' copies. */
        public Replay replay() {
            Token[] copies = copies();
            TokenList returnedCopies = new TokenList(returned.length);
            for (int id : returned) returnedCopies.add(copies[id]);
            return new Replay(rootList(copies), returnedCopies);
        }

        /** Copies made by one {@link MemoSnapshot#replay()}. */
        public record Replay(TokenList roots, TokenList returned) {}

        private TokenList rootList(Token[] copies) {
            TokenList result = new TokenList(roots.length);
            for (int root : roots) result.add(copies[root]);
            return result;
        }

        private Token[] copies() {
            Token[] result = new Token[nodes.size()];
            for (int i = nodes.size() - 1; i >= 0; i--) {
                MemoNode node = nodes.get(i);
                TokenList children = new TokenList(node.children.length);
                for (int child : node.children) children.add(result[child]);
                TokenList filtered = new TokenList(node.filtered.length);
                for (int child : node.filtered) filtered.add(result[child]);
                Token copy = new Token(node.token, node.parser, children, filtered);
                copyMemoMaps(node, copy);
                result[i] = copy;
            }
            return result;
        }

        private static void copyMemoMaps(MemoNode node, Token target) {
            for (int i = 0; i < node.extras.length; i += 2)
                target.extraObjects().put((Name) node.extras[i], node.extras[i + 1]);
            for (int i = 0; i < node.related.length; i += 2)
                target.relatedTokens().put((Name) node.related[i], (Token) node.related[i + 1]);
        }
    }

    /** Construct a checked memo copy without evaluating a custom Source or Parser. */
    private Token(Token original, Parser capturedParser, TokenList children, TokenList filtered) {
        tokenKind = original.tokenKind;
        source = original.source;
        tokenString = original.tokenString;
        tokenRange =
                original.tokenRange == null
                        ? null
                        : new Range(
                                original.tokenRange.startIndexInclusive,
                                original.tokenRange.endIndexExclusive);
        parser = capturedParser;
        parent = Optional.empty();
        originalChildren = children;
        filteredChildren = filtered;
        Optional<Token> self = children.isEmpty() ? null : Optional.of(this);
        for (Token child : children) child.parent = self;
    }

	public Token setParent(Token parentToken) {
		parent = Optional.ofNullable(parentToken);
		return this;
	}

	public Token setParent(Optional<Token> parentToken) {
		parent = parentToken;
		return this;
	}

	
//	public static Token empty(TokenKind tokenKind , EndExclusiveCursor position , Parser parser){
//	  StringSource empty = 
//	      StringSource.createDetachedSource("", null ,  new CodePointOffset(position.position()));
//	  
//		return new Token(tokenKind , empty ,parser);
//	}
	public static Token empty(TokenKind tokenKind , EndExclusiveCursor position , Parser parser){
	  // An empty token should still be anchored to the original/root coordinate system
	  // so that diagnostics (cursorRange, error ranges, etc.) can point to the correct location.
	  Source root = (position.positionResolver() instanceof Source)
	      ? (Source) position.positionResolver()
	      : StringSource.createRootSource("");
	  StringSource empty = StringSource.createSubSource(
	      "",
	      root,
	      new CodePointOffset(position.positionInRoot())
	  );
	  return new Token(tokenKind, empty, parser);
	}
	
	public Source getSource() {
		return source;
	}

    /**
     * Anchors a newly collected, real, detached empty token without losing its CST
     * children or side metadata. Existing coordinates, virtual tokens and custom
     * subclasses are untouched. The children move to the returned replacement;
     * this is intended for collection before the token is attached to its parent.
     */
    public Token anchorCollectedEmptySource(Source anchor) {
        if (anchor == null || !anchor.isEmpty() || anchor.sourceKind().isDetached()) {
            throw new IllegalArgumentException("anchor must be an empty source with root coordinates");
        }
        if (getClass() != Token.class || parent.isPresent() || !tokenKind.isReal()
                || source == null || !source.isEmpty() || !source.sourceKind().isDetached()) {
            return this;
        }
        Token copy = new Token(tokenKind, anchor, parser, originalChildren);
        copy.filteredChildren.clear();
        copy.filteredChildren.addAll(filteredChildren);
        copy.copySideMapsFrom(this);
        return copy;
    }
	
	@Deprecated
	public Optional<String> getToken() {
		return tokenString;
	}
	
	@Deprecated
	public Range getTokenRange() {
		return tokenRange;
	}
	
	@Deprecated
	public RangedString getRangedString() {
		if (source == null) {
			return new RangedString(0, "");
		}
		int start = source.cursorRange().startIndexInclusive.position().value();
		return new RangedString(start, source.sourceAsString());
	}
	
	public Parser getParser(){
		return parser;
	}
	
  public <T extends Parser> T  getParser(Class<T> parserClass) {
    return parserClass.cast(parser);
  }
  
  public <T extends Parser> TypedToken<T> typed(Class<T> parserClassOrInterface){
  	if(parserClassOrInterface.isInterface()) {
  		return typedWithInterface(parserClassOrInterface);
  	}
  	return new TypedToken<T>(this, Parser.get(parserClassOrInterface)).setParent(parent);
  }
  
  public <T extends Parser> TypedToken<T> typed(T parser){
  	return new TypedToken<T>(this, parser).setParent(parent);
  }
  
  public <T extends Parser> TypedToken<T> typedWithInterface(Class<T> parserInterface){
  	if(false == parserInterface.isInterface()) {
  		throw new IllegalArgumentException();
  	}
  	return new TypedToken<T>(this, parserInterface.cast(parser)).setParent(parent);
  }
		
	public TokenList flatten(){
		return flatten(ScanDirection.Depth ,ChildrenKind.astNodes);
	}
	
	public TokenList flatten(ScanDirection breadthOrDepth){
		return flatten(breadthOrDepth , ChildrenKind.astNodes);
	}
	
	public TokenList flatten(ScanDirection breadthOrDepth , ChildrenKind childrenKind){
		return breadthOrDepth == ScanDirection.Depth ?
				flattenDepth(childrenKind) : flattenBreadth(childrenKind);
	}
	
	public TokenList flattenDepth(ChildrenKind childrenKind){
	  TokenList list = new TokenList();
		list.add(this);
		for(Token child :children(childrenKind)){
			list.addAll(child.flattenDepth(childrenKind));
		}
		return list;
	}
	
	public TokenList flattenBreadth(ChildrenKind childrenKind){
	  TokenList list = new TokenList();
		Deque<Token> deque = new ArrayDeque<Token>();
		deque.add(this);
		while (false == deque.isEmpty()) {
			Token poll = deque.poll();
			list.add(poll);
			if(false ==poll.children(childrenKind).isEmpty()) {
				deque.addAll(poll.children(childrenKind));
			}
		}
		return list;
	}

	
	public enum ScanDirection{
		Breadth,
		Depth
	}
	
	@Override
	public String toString() {
		return displayString.get(this);
	}
	
	public boolean isTerminalSymbol(){
		return parser.forTerminalSymbol();
	}

	public TokenKind getTokenKind() {
		return tokenKind;
	}
	public Token replace(Parser replace) {
		this.parser = replace;
		return this;
	}
	
	public Token newWithReplace(Parser replace) {
	  return new Token(tokenKind, originalChildren, replace)
			  .setParent(parent);
	}
	
	public <P extends Parser>TypedToken<P> newWithReplaceTyped(P replace) {
	  return new Token(tokenKind, originalChildren, replace).typed(replace)
			  .setParent(parent);
	}

	
	public <P extends Parser>TypedToken<P> newWithReplacedParserTyped(P replace){
		return newWithReplacedParserTyped(replace , ChildrenKind.astNodes)
				.setParent(parent);
	}
	
	public Token newWithReplacedParser(Parser replace){
		return newWithReplacedParser(replace , ChildrenKind.astNodes);
	}

	
	public Token newWithReplacedParser(Parser replace , ChildrenKind childrenKind){
		if(false == children(childrenKind).isEmpty()){
			throw new IllegalArgumentException("not support collected token");
		}
		return new Token(tokenKind,source,replace).setParent(parent);
	}
	
	public <P extends Parser>TypedToken<P> newWithReplacedParserTyped(P replace , ChildrenKind childrenKind){
		if(false == children(childrenKind).isEmpty()){
			throw new IllegalArgumentException("not support collected token");
		}
		return new Token(tokenKind,source,replace)
				.typed(replace).setParent(parent);
	}

	
	TokenList children(ChildrenKind kind){
		return kind == ChildrenKind.astNodes ? 
				filteredChildren :
				originalChildren;
	}
	
	public Token newCreatesOf(TokenList newChildrens) {
		
		Token newToken = new Token(tokenKind , newChildrens , parser)
				.setParent(parent);
		return newToken;
	}
	
	@Deprecated
	public Token newCreatesOf(List<? extends Token> newChildrens) {
		return newCreatesOf(TokenList.of(new ArrayList<>(newChildrens)));
	}
	
	public Token newCreatesOf(Token... newChildrens) {
		
		return newCreatesOf(new TokenList(newChildrens));
	}
	
	@SuppressWarnings("unchecked")
	public Token newCreatesOf(Predicate<Token>... filterForChildrens) {
	  TokenList newChildren = TokenList.of( 
	      Stream.of(filterForChildrens)
    			.flatMap(this::getChildren)
    			.collect(Collectors.toList())
    );
		return newCreatesOf(TokenList.of(newChildren));
	}
	
	
	public Token newCreatesOf(TokenEffecterWithMatcher... tokenEffecterWithMatchers) {
		return newCreatesOf(ChildrenKind.astNodes, tokenEffecterWithMatchers);
	}
	
	public Token newCreatesOf(ChildrenKind kind , TokenEffecterWithMatcher... tokenEffecterWithMatchers) {
	  TokenList newChildren = TokenList.of(
	      children(kind).stream()
    			.map(token->{
    				for (TokenEffecterWithMatcher tokenEffecterWithMatcher : tokenEffecterWithMatchers) {
    					if(tokenEffecterWithMatcher.target.test(token)) {
    						return tokenEffecterWithMatcher.effector.apply(token);
    					}
    				}
    				return token;
    			})
    			.collect(Collectors.toList())
		);
		return newCreatesOf(TokenList.of(newChildren));
	}
	
	public Token getDirectAncestor(Predicate<Token> predicates){
		return getDirectAncestorAsOptional(predicates).orElseThrow();
	}

	public Optional<Token> getDirectAncestorAsOptional(Predicate<Token> predicates){
		Token current = this;
		while(true) {
			if(current.parent.isEmpty()) {
				return Optional.empty();
			}
			Token parentToken = current.parent.get();
			if(predicates.test(parentToken)) {
				return Optional.of(parentToken);
			}
			current = parentToken;
		}
	}
	
	public Token getAncestor(Predicate<Token> predicates){
		return getAncestorAsOptional(predicates).orElseThrow();
	}
	
	public Optional<Token> getAncestorAsOptional(Predicate<Token> predicates){
		int level=0;
		Token current = this;
		while(true) {
			if(current.parent.isEmpty()) {
				return Optional.empty();
			}
			Token parentToken = current.parent.get();
			if(predicates.test(parentToken)) {
				return Optional.of(parentToken);
			}
			if(level>0) {
				for(Token child : parentToken.originalChildren) {
					if(predicates.test(child)) {
						return Optional.of(child);
					}
				}
			}
			current = parentToken;
			level++;
		}
	}
	
	//FIXME! 現在flattenしてから探しているものをinterface化する
	public interface AbstractDescendantFinder{
		
	}
	
	public Token getChild(Predicate<Token> predicates) {
		return getChild(predicates , ChildrenKind.astNodes);
	}
	
	public Token getChild(Predicate<Token> predicates , ChildrenKind childrenKind) {
		return children(childrenKind).stream().filter(predicates).findFirst().orElseThrow();
	}
	
	public int getChildIndex(Predicate<Token> predicates) {
		return getChildIndex(ChildrenKind.astNodes, predicates);
	}

	public int getChildIndex(ChildrenKind childrenKind, Predicate<Token> predicates) {
		
		int index=0;
		for (Token token : children(childrenKind)) {
			if(predicates.test(token)) {
				return index;
			}
			index++;
		}
		throw new IllegalArgumentException("predicates is not match");
	}
	
	public Token getChildWithParser(Predicate<Parser> predicatesWithTokensParser) {
		return getChildWithParser(predicatesWithTokensParser,ChildrenKind.astNodes);
	}
	
	public Token getChildWithParser(Predicate<Parser> predicatesWithTokensParser , ChildrenKind childrenKind) {
		return children(childrenKind).stream().filter(token-> predicatesWithTokensParser.test(token.parser)).findFirst().orElseThrow();
	}
	
	public int getChildIndexWithParser(Predicate<Parser> predicatesWithTokensParser) {
		return getChildIndexWithParser(ChildrenKind.astNodes, predicatesWithTokensParser);
	}
	
	public int getChildIndexWithParser(ChildrenKind childrenKind, Predicate<Parser> predicatesWithTokensParser) {
		
		int index=0;
		for (Token token : children(childrenKind)) {
			if(predicatesWithTokensParser.test(token.parser)) {
				return index;
			}
			index++;
		}
		throw new IllegalArgumentException("predicates is not match");
	}

	
	public Token getChildWithParser(Class<? extends Parser> parserClass) {
		return getChildWithParser(parser -> parser.getClass() == parserClass);
	}
	
	public <P extends Parser>TypedToken<P> getChildWithParserTyped(Class<P> parserClass) {
		return getChildWithParser(parser -> parser.getClass() == parserClass).typed(parserClass);
	}

	
	public int getChildIndexWithParser(Class<? extends Parser> parserClass) {
		return getChildIndexWithParser(ChildrenKind.astNodes, parserClass);
	}
	
	public int getChildIndexWithParser(ChildrenKind childrenKind, Class<? extends Parser> parserClass) {
		
		return getChildIndexWithParser(childrenKind , parser -> parser.getClass() == parserClass);
	}
	
	public Optional<Token> getChildAsOptional(Predicate<Token> predicates ) {
		return getChildAsOptional(predicates , ChildrenKind.astNodes);
	}
	public Optional<Token> getChildAsOptional(Predicate<Token> predicates ,ChildrenKind childrenKind) {
		return children(childrenKind).stream().filter(predicates).findFirst();
	}
	
	public Optional<Token> getChildWithParserAsOptional(Predicate<Parser> predicatesWithTokensParser){
		return getChildWithParserAsOptional(predicatesWithTokensParser , ChildrenKind.astNodes);
	}
	public Optional<Token> getChildWithParserAsOptional(Predicate<Parser> predicatesWithTokensParser,
			ChildrenKind childrenKind) {
		return children(childrenKind).stream().filter(token-> predicatesWithTokensParser.test(token.parser)).findFirst();
	}
	
	public Optional<Token> getChildWithParserAsOptional(Class<? extends Parser> parserClass) {
		return getChildWithParserAsOptional(parser -> parser.getClass() == parserClass);
	}
	
	public <P extends Parser> Optional<TypedToken<P>> getChildWithParserAsOptionalTyped(Class<P> parserClass) {
		return getChildWithParserAsOptional(parser -> parser.getClass() == parserClass).map(token->token.typed(parserClass));
	}

	
	public Stream<Token> getChildren(Predicate<Token> predicates) {
		return getChildren(predicates , ChildrenKind.astNodes);
	}
	public Stream<Token> getChildren(Predicate<Token> predicates , ChildrenKind childrenKind) {
		return children(childrenKind).stream().filter(predicates);
	}
	
	public Stream<Token> getChildrenWithParser(Predicate<Parser> predicatesWithTokensParser){
		return getChildrenWithParser(predicatesWithTokensParser , ChildrenKind.astNodes);
	}
	public Stream<Token> getChildrenWithParser(Predicate<Parser> predicatesWithTokensParser,
			ChildrenKind childrenKind) {
		return children(childrenKind).stream().filter(token-> predicatesWithTokensParser.test(token.parser));
	}
	
	public Stream<Token> getChildrenWithParser(Class<? extends Parser> parserClass) {
		return getChildrenWithParser(parser -> parser.getClass() == parserClass);
	}
	
	public <P extends Parser> Stream<TypedToken<P>> getChildrenWithParserTyped(Class<P> parserClass) {
		return getChildrenWithParser(parser -> parser.getClass() == parserClass).map(token->token.typed(parserClass));
	}

	
	public TokenList getChildrenAsList(Predicate<Token> predicates) {
		return TokenList.of(getChildren(predicates).collect(Collectors.toList()));
	}
	
	public TokenList getChildrenWithParserAsList(Predicate<Parser> predicatesWithTokensParser) {
		return TokenList.of(getChildrenWithParser(predicatesWithTokensParser).collect(Collectors.toList()));
	}
	
	public TokenList getChildrenWithParserAsList(Class<? extends Parser> parserClass) {
		return getChildrenWithParserAsList(parser -> parser.getClass() == parserClass);
	}
	
	public <P extends Parser> List<TypedToken<P>> getChildrenWithParserAsListTyped(Class<P> parserClass) {
		return getChildrenWithParserTyped(parserClass).collect(Collectors.toList());
	}

	
	public Token getChildFromOriginal(int index) {
		return children(ChildrenKind.original).get(index);
	}
	
	public Token getChildFromAstNodes(int index) {
		return filteredChildren.get(index);
	}
	
	public TokenList getOriginalChildren() {
		return originalChildren;
	}

	public TokenList getAstNodeChildren() {
		return filteredChildren;
	}
	
	public Token addChildren(Token...tokens) {
		
		for (Token token : tokens) {
			originalChildren.add(token);
			if(AST_NODES.test(token)) {
				filteredChildren.add(token);
			}
		}
		return this;
	}
	
	/** Copies whatever side entries {@code source} holds; nothing is allocated when it holds none. */
	private void copySideMapsFrom(Token source) {
		Map<Name,Object> extras = source.extraObjectByName;
		if (extras != null && false == extras.isEmpty()) {
			extraObjects().putAll(extras);
		}
		Map<Name,Token> related = source.relatedTokenByName;
		if (related != null && false == related.isEmpty()) {
			relatedTokens().putAll(related);
		}
	}

	/* The maps themselves stay concurrent, so their creation is published safely as well. */
	private Map<Name,Object> extraObjects() {
		Map<Name,Object> map = extraObjectByName;
		if (map == null) {
			synchronized (this) {
				map = extraObjectByName;
				if (map == null) {
					map = new NullSafetyConcurrentHashMap<>();
					extraObjectByName = map;
				}
			}
		}
		return map;
	}

	private Map<Name,Token> relatedTokens() {
		Map<Name,Token> map = relatedTokenByName;
		if (map == null) {
			synchronized (this) {
				map = relatedTokenByName;
				if (map == null) {
					map = new NullSafetyConcurrentHashMap<>();
					relatedTokenByName = map;
				}
			}
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	public<T> Optional<T> getExtraObject(Name name) {
		Map<Name,Object> map = extraObjectByName;
		return map == null ? Optional.empty() : Optional.ofNullable( (T)map.get(name));
	}
	
	public void putExtraObject(Name name , Object object) {
		// NullSafetyConcurrentHashMap drops null keys and values, so a null pair stores nothing.
		if (name == null || object == null) {
			return;
		}
		extraObjects().put(name , object);
	}
	
	public boolean removeExtraObject(Name name) {
		Map<Name,Object> map = extraObjectByName;
		if (map == null) {
			return false;
		}
		var preset = map.remove(name);
		return preset != null ; 
	}
	
	public Optional<Token> getRelatedToken(Name name) {
		Map<Name,Token> map = relatedTokenByName;
		return map == null ? Optional.empty() : Optional.ofNullable( map.get(name));
	}
	
	public void putRelatedToken(Name name , Token relatedToken) {
		if (name == null || relatedToken == null) {
			return;
		}
		relatedTokens().put(name , relatedToken);
	}
	
	public Optional<Token>  removeRelatedToken(Name name) {
		Map<Name,Token> map = relatedTokenByName;
		return map == null ? Optional.empty() : Optional.ofNullable(map.remove(name));
	}
	
	public String getPath() {
		return getPath(parser.getName(NameKind.computedName).getSimpleName());
	}
	
	String getPath(String path) {
		if(parent.isEmpty()) {
			return "/"+path;
		}
		return parent.get().getPath(parent.get().parser.getName(NameKind.computedName).getSimpleName()+"/" + path);
	}
}
