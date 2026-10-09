package org.unlaxer.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import org.unlaxer.source.DocumentSnapshot;
import org.unlaxer.source.SegmentSourceMap;

/** Bounded demand evaluation for coarse analysis phases, separate from parser recursion. */
public final class AnalysisPipeline {
    public enum State { COMPLETE, PARTIAL, FAILED, INACTIVE, DEFERRED, CYCLE, LIMIT, UNSUPPORTED }
    public record Artifact(State state, String payload, List<SegmentSourceMap.Location> origins,
                           List<SegmentSourceMap.Mapping> diagnostics) {
        public Artifact {
            Objects.requireNonNull(state); Objects.requireNonNull(payload);
            origins = List.copyOf(origins); diagnostics = List.copyOf(diagnostics);
        }
        public static Artifact empty(State state) { return new Artifact(state, "", List.of(), List.of()); }
    }
    public record Phase(String id, List<String> dependencies, List<String> inputs,
                        List<String> configurationKeys, boolean executesUserCode) {
        public Phase {
            if (Objects.requireNonNull(id).isEmpty()) { throw new IllegalArgumentException("empty phase id"); }
            dependencies = unique(dependencies); inputs = unique(inputs); configurationKeys = unique(configurationKeys);
        }
        private static List<String> unique(List<String> values) {
            List<String> result = List.copyOf(values);
            if (new HashSet<>(result).size() != result.size() || result.contains("")) {
                throw new IllegalArgumentException("duplicate or empty phase key");
            }
            return result;
        }
    }
    public record Request(Phase phase, Map<String, DocumentSnapshot> inputs, Map<String, String> configuration,
                          Map<String, Artifact> dependencies) {
        public Request {
            inputs = Map.copyOf(inputs); configuration = Map.copyOf(configuration); dependencies = Map.copyOf(dependencies);
        }
    }
    public interface Executor { Artifact execute(Request request); }
    public record Result(Artifact artifact, OptionalLong revision, List<String> evaluated, List<String> reused) {
        public Result { evaluated = List.copyOf(evaluated); reused = List.copyOf(reused); }
    }
    private record Signature(List<DocumentSnapshot> inputs, Map<String, String> configuration, List<Long> dependencies) {}
    private record Cached(Signature signature, Artifact artifact, long revision) {}
    private record Value(Artifact artifact, long revision) {}
    private final Map<String, Phase> phases = new LinkedHashMap<>();
    private final Map<String, Executor> executors;
    private final Map<String, Cached> cache = new HashMap<>();
    private long revision;

    /** Executors are fixed for this pipeline's lifetime and must be deterministic over their request. */
    public AnalysisPipeline(List<Phase> definitions, Map<String, Executor> executors) {
        this.executors = Map.copyOf(executors);
        for (Phase phase : definitions) {
            if (phases.put(phase.id, phase) != null) { throw new IllegalArgumentException("duplicate phase"); }
        }
        for (Phase phase : definitions) {
            for (String dependency : phase.dependencies) {
                if (false == phases.containsKey(dependency)) { throw new IllegalArgumentException("missing phase dependency"); }
            }
        }
    }
    public Result evaluate(String id, Map<String, DocumentSnapshot> snapshots, Map<String, String> configuration,
                           int maximumPhases, boolean allowUserCode) {
        if (maximumPhases < 0 || maximumPhases > 256 || false == phases.containsKey(id)) { throw new IllegalArgumentException("invalid evaluation"); }
        Evaluation evaluation = new Evaluation(Map.copyOf(snapshots), Map.copyOf(configuration), maximumPhases, allowUserCode);
        Value value = evaluation.visit(id);
        return new Result(value.artifact, value.revision < 0 ? OptionalLong.empty() : OptionalLong.of(value.revision),
                evaluation.evaluated, evaluation.reused);
    }
    public void clearCache() { cache.clear(); }
    private final class Evaluation {
        private final Map<String, DocumentSnapshot> snapshots;
        private final Map<String, String> configuration;
        private final int maximum;
        private final boolean allowUserCode;
        private final Set<String> active = new HashSet<>();
        private final Map<String, Value> finished = new HashMap<>();
        private final List<String> evaluated = new ArrayList<>();
        private final List<String> reused = new ArrayList<>();
        private int visited;
        private Evaluation(Map<String, DocumentSnapshot> snapshots, Map<String, String> configuration, int maximum, boolean allowUserCode) {
            this.snapshots = snapshots; this.configuration = configuration; this.maximum = maximum; this.allowUserCode = allowUserCode;
        }
        private Value incomplete(State state) { return new Value(Artifact.empty(state), -1); }
        private Value visit(String id) {
            if (active.contains(id)) { return incomplete(State.CYCLE); }
            if (finished.containsKey(id)) { return finished.get(id); }
            if (visited++ >= maximum) { return incomplete(State.LIMIT); }
            Phase phase = phases.get(id);
            if (phase.executesUserCode && false == allowUserCode) { return incomplete(State.UNSUPPORTED); }
            Executor executor = executors.get(id);
            if (executor == null) { return incomplete(State.UNSUPPORTED); }
            Map<String, DocumentSnapshot> inputs = new LinkedHashMap<>();
            for (String key : phase.inputs) {
                DocumentSnapshot input = snapshots.get(key);
                if (input == null) { return incomplete(State.DEFERRED); }
                inputs.put(key, input);
            }
            Map<String, String> settings = new LinkedHashMap<>();
            for (String key : phase.configurationKeys) {
                if (configuration.containsKey(key)) { settings.put(key, configuration.get(key)); }
            }
            active.add(id);
            Map<String, Artifact> dependencies = new LinkedHashMap<>();
            List<Long> versions = new ArrayList<>();
            try {
                for (String dependency : phase.dependencies) {
                    Value value = visit(dependency);
                    if (value.revision < 0) { return value; }
                    dependencies.put(dependency, value.artifact);
                    versions.add(value.revision);
                }
                Signature signature = new Signature(List.copyOf(inputs.values()), Map.copyOf(settings), List.copyOf(versions));
                Cached previous = cache.get(id);
                if (previous != null && previous.signature.equals(signature)) {
                    reused.add(id);
                    Value value = new Value(previous.artifact, previous.revision);
                    finished.put(id, value);
                    return value;
                }
                Artifact artifact = Objects.requireNonNull(executor.execute(new Request(phase, inputs, settings, dependencies)));
                evaluated.add(id);
                if (artifact.state == State.DEFERRED || artifact.state == State.CYCLE || artifact.state == State.LIMIT
                        || artifact.state == State.UNSUPPORTED) { return new Value(artifact, -1); }
                Cached next = new Cached(signature, artifact, ++revision);
                cache.put(id, next);
                Value value = new Value(artifact, next.revision);
                finished.put(id, value);
                return value;
            } finally { active.remove(id); }
        }
    }
}
