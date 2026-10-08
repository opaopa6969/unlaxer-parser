package org.unlaxer.dsl.semantic;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Transactional, bounded query cache. Executors read every dependency through Context. */
public final class SemanticQueries implements AutoCloseable {
    public record Key(String kind, String identity, String arguments) {
        public Key { require(kind); require(identity); utf8(arguments); }
    }
    public record Input(String uri, long version, String value) {
        public Input { utf8(uri); utf8(value); if(version<0) throw new IllegalArgumentException("negative version"); }
    }
    public record Limits(int entries, long bytes, int inputs, int steps, int dependencies) {
        public Limits { if(entries<0 || bytes<0 || inputs<0 || steps<1 || steps>4096 || dependencies<1 || dependencies>4096) throw new IllegalArgumentException("invalid limits"); }
    }
    public enum Failure { CANCELLED, CLOSED, CYCLE, LIMIT, STALE, UNSUPPORTED }
    public static final class QueryException extends RuntimeException {
        private final Failure failure;
        private QueryException(Failure failure) { super(failure.name()); this.failure=failure; }
        public Failure failure() { return failure; }
    }
    public static final class Cancellation {
        private final AtomicBoolean cancelled=new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
    }
    public interface Executor { String execute(Key key, Context context); }
    public interface Context {
        Optional<Input> input(String key);
        String query(Key key);
        void checkpoint();
    }
    public record Dependency(Key key, long revision) {}
    public record Stats(long hits, long evaluated, long evicted, int entries, long bytes, long maximumBytes, int maximumEntries) {}
    public static final class Result {
        private final long owner, projectVersion, revision;
        private final String project, value;
        private final Key key;
        private final Cancellation cancellation;
        private final Map<String, Long> inputs;
        private final List<Dependency> queries;
        private Result(long owner,String project,long projectVersion,Key key,Cached cached,Cancellation cancellation) {
            this.owner=owner; this.project=project; this.projectVersion=projectVersion; this.key=key;
            this.revision=cached.revision; this.value=cached.value; this.inputs=cached.inputs; this.queries=cached.queries; this.cancellation=cancellation;
        }
        public String project() { return project; }
        public long projectVersion() { return projectVersion; }
        public Key key() { return key; }
        public long revision() { return revision; }
        /** Inspectable historical data. Call accept before publishing diagnostics or applying edits. */
        public String value() { return value; }
        public Map<String, Long> inputs() { return inputs; }
        public List<Dependency> queries() { return queries; }
    }
    private record Versioned(Input input,long revision) {}
    private record Cached(String value,long revision,Map<String,Long> inputs,List<Dependency> queries,long bytes) {}
    private static final AtomicLong OWNERS=new AtomicLong();
    private final long owner=OWNERS.incrementAndGet();
    private final String project;
    private final Limits limits;
    private final Map<String,Executor> executors;
    private final LinkedHashMap<Key,Cached> cache=new LinkedHashMap<>();
    private Map<String,Versioned> inputs=Map.of();
    private long version=-1, revision, hits, evaluated, evicted, bytes, maximumBytes;
    private int maximumEntries;
    private boolean closed, busy;
    public SemanticQueries(String project,Limits limits,Map<String,Executor> executors) {
        require(project); this.project=project; this.limits=Objects.requireNonNull(limits); this.executors=new HashMap<>(Map.copyOf(executors));
        executors.keySet().forEach(SemanticQueries::require);
    }
    private static void require(String text) { if(text==null || text.isEmpty()) throw new IllegalArgumentException("empty key"); utf8(text); }
    private static void utf8(String text) { Objects.requireNonNull(text); if(text.codePoints().anyMatch(cp->cp>=0xD800 && cp<=0xDFFF)) throw new IllegalArgumentException("invalid Unicode scalar"); }
    private static QueryException failure(Failure f) { return new QueryException(f); }
    private void open() { if(closed) throw failure(Failure.CLOSED); }
    private void idle() { open(); if(busy) throw new IllegalStateException("query database is evaluating"); }
    /** Atomically replace the current input snapshot. Missing input reads are dependencies too. */
    public synchronized void update(long nextVersion,Map<String,Input> replacement) {
        idle(); if(nextVersion<=version) throw failure(Failure.STALE);
        if(replacement.size()>limits.inputs) throw failure(Failure.LIMIT);
        replacement.keySet().forEach(SemanticQueries::require);
        Map<String,Versioned> next=new LinkedHashMap<>();
        Map<String,Input> ordered=new TreeMap<>((a,b)->Arrays.compare(a.codePoints().toArray(),b.codePoints().toArray())); ordered.putAll(replacement);
        replacement.forEach((key,value)->{ Objects.requireNonNull(value); Versioned old=inputs.get(key);
            if(old!=null && old.input.uri.equals(value.uri) && (value.version<old.input.version || value.version==old.input.version && !value.value.equals(old.input.value))) throw failure(Failure.STALE);
        });
        ordered.forEach((key,value)->{ Versioned old=inputs.get(key); next.put(key,old!=null && old.input.equals(value)?old:new Versioned(value,++revision)); });
        inputs=Map.copyOf(next); version=nextVersion;
        Iterator<Map.Entry<Key,Cached>> it=cache.entrySet().iterator();
        while(it.hasNext()) { Cached cached=it.next().getValue(); if(!valid(cached)) { bytes-=cached.bytes; it.remove(); } }
    }
    private long inputRevision(String id) { Versioned input=inputs.get(id); return input==null?0:input.revision; }
    private boolean valid(Cached cached) { return cached.inputs.entrySet().stream().allMatch(e->inputRevision(e.getKey())==e.getValue()); }
    public synchronized Result evaluate(Key key,Cancellation cancellation) {
        idle(); Objects.requireNonNull(key); Objects.requireNonNull(cancellation); if(version<0) throw failure(Failure.STALE);
        busy=true;
        try {
            Evaluation transaction=new Evaluation(cancellation); Cached result=transaction.visit(key); transaction.checkpoint();
            // Publication is all-or-nothing: cancellation, exceptions and limits never insert partial work.
            for(var entry:transaction.staged.entrySet()) put(entry.getKey(),entry.getValue());
            hits+=transaction.hits; evaluated+=transaction.evaluated;
            return new Result(owner,project,version,key,result,cancellation);
        } finally { busy=false; }
    }
    private void put(Key key,Cached value) {
        Cached old=cache.remove(key); if(old!=null) bytes-=old.bytes;
        if(limits.entries==0 || value.bytes>limits.bytes) return;
        while(cache.size()>=limits.entries || bytes>limits.bytes-value.bytes) {
            var oldest=cache.entrySet().iterator(); Cached removed=oldest.next().getValue(); oldest.remove(); bytes-=removed.bytes; evicted++;
        }
        cache.put(key,value); bytes+=value.bytes; maximumBytes=Math.max(maximumBytes,bytes); maximumEntries=Math.max(maximumEntries,cache.size());
    }
    public synchronized boolean isCurrent(Result result) { return !closed && result.owner==owner && result.projectVersion==version && !result.cancellation.isCancelled(); }
    /** Use immediately on the same serialized editor lane before publishing a diagnostic or edit. */
    public synchronized String accept(Result result) { open(); if(result.cancellation.isCancelled()) throw failure(Failure.CANCELLED); if(!isCurrent(result)) throw failure(Failure.STALE); return result.value; }
    public synchronized Stats stats() { return new Stats(hits,evaluated,evicted,cache.size(),bytes,maximumBytes,maximumEntries); }
    public synchronized int retainedInputs() { return inputs.size(); }
    @Override public synchronized void close() { if(busy) throw new IllegalStateException("query database is evaluating"); cache.clear(); inputs=Map.of(); executors.clear(); bytes=0; closed=true; }
    private static long size(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }
    private static long size(Key k) { return 24+size(k.kind)+size(k.identity)+size(k.arguments); }
    private final class Evaluation {
        private final Cancellation cancellation;
        private final LinkedHashMap<Key,Cached> staged=new LinkedHashMap<>();
        private final Set<Key> active=new HashSet<>();
        private int steps;
        private long hits,evaluated;
        private QueryException broken;
        private Evaluation(Cancellation cancellation) { this.cancellation=cancellation; }
        private void checkpoint() { if(cancellation.isCancelled()) throw failure(Failure.CANCELLED); if(broken!=null) throw broken; }
        private Cached visit(Key key) {
            try { return visitInner(key); } catch(QueryException e) { broken=e; throw e; }
        }
        private Cached visitInner(Key key) {
            checkpoint(); if(++steps>limits.steps || active.size()>=128) throw failure(Failure.LIMIT);
            if(active.contains(key)) throw failure(Failure.CYCLE);
            Cached found=staged.get(key); if(found==null) found=cache.get(key);
            if(found!=null) { hits++; staged.remove(key); staged.put(key,found); return found; }
            Executor executor=executors.get(key.kind); if(executor==null) throw failure(Failure.UNSUPPORTED);
            active.add(key); Map<String,Long> reads=new TreeMap<>(); List<Dependency> children=new ArrayList<>(); boolean[] available={true};
            try {
                Context context=new Context() {
                    private void bounded() { if(reads.size()+children.size()>limits.dependencies) { broken=failure(Failure.LIMIT); throw broken; } }
                    @Override public Optional<Input> input(String id) { checkpoint(); require(id); reads.put(id,inputRevision(id)); bounded(); Versioned input=inputs.get(id); return input==null?Optional.empty():Optional.of(input.input); }
                    @Override public String query(Key child) { checkpoint(); Cached cached=visit(child); reads.putAll(cached.inputs); children.add(new Dependency(child,cached.revision)); bounded(); return cached.value; }
                    @Override public void checkpoint() { if(!available[0]) throw new IllegalStateException("expired query context"); Evaluation.this.checkpoint(); }
                };
                String value=Objects.requireNonNull(executor.execute(key,context)); utf8(value); checkpoint();
                long weight=64+size(key)+size(value);
                for(String input:reads.keySet()) weight+=16+size(input);
                for(Dependency child:children) weight+=16+size(child.key);
                Cached cached=new Cached(value,++revision,Map.copyOf(reads),List.copyOf(children),weight); staged.put(key,cached); evaluated++; return cached;
            } finally { available[0]=false; active.remove(key); }
        }
    }
}
