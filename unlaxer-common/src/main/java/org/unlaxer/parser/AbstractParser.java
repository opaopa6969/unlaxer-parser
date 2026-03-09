package org.unlaxer.parser;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.unlaxer.Committed;
import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.Tag;
import org.unlaxer.TokenKind;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.context.ParseContext;

public abstract class AbstractParser implements Parser {
	
	private static final long serialVersionUID = -7497886240652402031L;
	
	Set<Tag> tags = new HashSet<>();

	Name name;
	
	Name specifiedName;

	Parser parser;

	Parsers children;

	public Optional<Parser> parent;
	
	Optional<Parser> root = Optional.empty();
	
	Map<Name,Parser> parserByName = new HashMap<>();
	
	boolean donePrepareChildren = false;
	
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
		
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		
		parseContext.begin(this);
		Parsed parsed = getParser().parse(parseContext,tokenKind,invertMatch);
		
		if(parsed.isSucceeded()){
			Committed commited = parseContext.commit(this , tokenKind);

			Parsed succeededParsed = new Parsed(commited);
			parseContext.endParse(this, succeededParsed , parseContext, tokenKind, invertMatch);
			return succeededParsed;
		}
		
		parseContext.rollback(this);
		parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
		return Parsed.FAILED;
	}

	public Parser getParser() {
		
		if (parser == null) {
			parser = createParser();
			if(this != parser){
				parser.setParent(this);

				if(false ==getChildren().contains(parser)){
					getChildren().add(parser);
				}
			}
		}
		createParserAndThen(parser);
		return parser;
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
		if(false == root.isPresent()){
			root = Optional.of(findFirstToParent(Parser.isRoot).orElse(this));
		}
		return root.get();
	}

	@Override
	public void setParent(Parser parent) {
		this.parent = Optional.ofNullable(parent);
		nodeReduceMarker.parent = Optional.of(nodeReduceMarker);
	}

	@Override
	public Parsers getChildren() {
		if(false == donePrepareChildren){
			prepareChildren(children);
			donePrepareChildren = true;
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