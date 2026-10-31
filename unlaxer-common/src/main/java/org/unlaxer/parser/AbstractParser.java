package org.unlaxer.parser;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.Tag;
import org.unlaxer.TokenKind;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.PackratMemoTable;

public abstract class AbstractParser implements Parser {
	
	private static final long serialVersionUID = -7497886240652402031L;
	
	Set<Tag> tags = new HashSet<>();

	Name name;
	
	Name specifiedName;

	/**
	 * The delegate that the inherited {@link #parse} runs, created on first use. Library and generated
	 * parsers override parse and return this from createParser, so only user parsers that inherit parse
	 * read it on every call. Several contexts may reach the first use of a shared graph at once (#1007):
	 * see {@link #getParser()}.
	 */
	volatile Parser parser;

	Parsers children;

	public Optional<Parser> parent;
	
	/**
	 * Lazily resolved root. Several contexts may parse a shared graph at once (#1007): the field is
	 * read once per call and only ever holds an immutable Optional, so a racing reader sees either
	 * empty or a complete value (final field semantics, JLS 17.5) and recomputes the same root.
	 */
	Optional<Parser> root = Optional.empty();
	
	/** Name lookups resolved from the root; concurrent parses of a shared graph read and fill it (#1007). */
	Map<Name,Parser> parserByName = new ConcurrentHashMap<>();
	
	volatile boolean donePrepareChildren = false;
	
	NodeReduceMarker nodeReduceMarker;
	
	ASTNodeKind astNodeKind;
	
	public AbstractParser() {
		this(null, new Parsers());
	}
	
	public AbstractParser(Name name) {
		this(name,new Parsers());
	}
	
	public AbstractParser(Parsers children) {
		this(null,children);
	}
	
	public AbstractParser(Name name , Parsers children) {
		super();
		this.specifiedName = name == null ? Name.of(getClass()) : name; 
		this.name = name == null ? Name.of(getClass()) : Name.of(getClass(),name);
		this.parent = Optional.empty();
		this.children = children;
		children.stream()
			.forEach(child->child.setParent(this));
		nodeReduceMarker = new NodeReduceMarker();
	}
	
	@Override
	public Parsed parse(ParseContext parseContext , TokenKind tokenKind ,boolean invertMatch) {
		parseContext.enterParserCall();
		try {
			// Opt-in safe failure memoization. Generated exact-class markers keep state-dependent
			// parsers out; safe successes also replay their token trees. Off by default.
			PackratMemoTable.Entry memo = PackratMemoTable.lookup(parseContext, this, tokenKind, invertMatch);
			if (memo != null) {
				return PackratMemoTable.replay(parseContext, this, tokenKind, invertMatch, memo);
			}
			var memoDiagnostic = PackratMemoTable.beginFailure(parseContext, this);
			var memoStart = PackratMemoTable.successKey(parseContext, this, tokenKind, invertMatch, memoDiagnostic);

			parseContext.startParse(this, parseContext, tokenKind, invertMatch);

			parseContext.begin(this);
			Parsed parsed = getParser().parse(parseContext,tokenKind,invertMatch);

			if(parsed.isSucceeded()){
				return PackratMemoTable.commitSuccess(parseContext, this, tokenKind, invertMatch,
					memoStart, memoDiagnostic, null, null);
			}

			parseContext.rollback(this);
			parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
			PackratMemoTable.memoizeFailure(parseContext, this, tokenKind, invertMatch, memoDiagnostic);
			return Parsed.FAILED;
		} catch (RuntimeException | Error failure) {
			parseContext.failParserCall(failure);
			throw failure;
		} finally {
			parseContext.leaveParserCall();
		}
	}

	/**
	 * Returns the delegate, creating it on the first call. Creating it can attach the delegate as a
	 * child, which changes the graph, so it runs once under this parser's monitor (the one that
	 * prepares the children) and the field is written only after the delegate has its parent and is a
	 * child. A thread that reads a non-null field therefore sees the attached delegate, a failed
	 * creation publishes nothing and the next call retries, and concurrent first uses create one
	 * delegate. The child list is replaced rather than changed in place, so another thread walking the
	 * children meanwhile sees the list either before or after the attachment.
	 */
	public Parser getParser() {
		Parser created = parser;
		if (created == null) {
			created = createAttachedParser();
		}
		createParserAndThen(created);
		return created;
	}

	private synchronized Parser createAttachedParser() {
		Parser created = parser;
		if (created != null) {
			return created;
		}
		created = createParser();
		if(this != created){
			created.setParent(this);
			Parsers attachedTo = getChildren();
			if(false == attachedTo.contains(created)){
				attachedTo.appendByCopy(created);
			}
		}
		parser = created;
		return created;
	}

	public void createParserAndThen(Parser createdParser){
		
	}
	
	public abstract Parser createParser();

	@Override
	public Optional<Parser> getParent() {
		return parent;
	}
	
	@Override
	public Parser getRoot(){
		Optional<Parser> resolved = root;
		if(false == resolved.isPresent()){
			resolved = Optional.of(findFirstToParent(Parser.isRoot).orElse(this));
			root = resolved;
		}
		return resolved.get();
	}

	@Override
	public void setParent(Parser parent) {
		this.parent = Optional.ofNullable(parent);
		nodeReduceMarker.parent = Optional.of(nodeReduceMarker);
	}

	/**
	 * Parsers are shared singletons, so the lazy child preparation must happen exactly once even
	 * when several threads start parsing at the same time. Without the lock a second thread could
	 * observe the container while the first one is still filling it and fail with a
	 * ConcurrentModificationException in the choice/chain iteration (#202); the volatile flag
	 * publishes the fully prepared list to readers that skip the lock.
	 */
	@Override
	public Parsers getChildren() {
		if(false == donePrepareChildren){
			synchronized (this) {
				if(false == donePrepareChildren){
					prepareChildren(children);
					donePrepareChildren = true;
				}
			}
		}
		return children;
	}

	@Override
	public Name getName(NameKind nameKind) {
		return nameKind.isSpecifiedName() ? specifiedName : name;
	}

	@Override
	public Set<Tag> getTags() {
		return tags;
	}

	@Override
	public Optional<Parser> getParser(Name name) {
		
		if(name == null){
			// The concurrent map has no null key; the HashMap answered this from the root uncached.
			return findFirstFromRoot(parser->parser.getName().equals(name));
		}
		Parser matched = parserByName.get(name);
		if(matched == null ){
			Optional<Parser> findFirstFromRoot = findFirstFromRoot(parser->parser.getName().equals(name));
			findFirstFromRoot.ifPresent(parser->parserByName.put(name , parser));
			return findFirstFromRoot;
		}
		return Optional.of(matched);
	}
	
	
	@Override
	public Stream<Parser> getPathStream(boolean containCallerParser){
		List<Parser> describeParents = findParents(Parser.isRoot,containCallerParser);
		Collections.reverse(describeParents);
		
		return describeParents.stream();
	}

	@Override
	public NodeReduceMarker getNodeReduceMarker() {
		return nodeReduceMarker;
	}

  @Override
  public Parser setASTNodeKind(ASTNodeKind astNodeKind) {
    this.astNodeKind = astNodeKind;
    addTag(astNodeKind.tag()); // TODO too much?
    return this;
  }

  @Override
  public ASTNodeKind astNodeKind() {
    
    if(astNodeKind == null) {
      return ASTNodeKind.NotSpecified;
    }
    return astNodeKind;
  }
}
