package org.unlaxer.dsl.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.TokenList;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.TransactionalState;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;

/**
 * UBNF の {@code @scopeTree} / {@code @declares} / {@code @backref} アノテーションが
 * 使用するランタイムスコープストア。
 *
 * <p>レキシカルスコープのネスト管理と、スコープ内のシンボル宣言・参照解決を担う。
 * {@link ParseContext#getGlobalScopeTreeMap()} を介してパースコンテキストに紐付けられる。
 *
 * <p>{@link ParseContext#registerTransactionalState(TransactionalState)} で所有状態を登録する。
 * スコープの push/pop、宣言、参照、診断の変更はパーサーのロールバック時に
 * 自動的に巻き戻される。初回利用がトランザクション内でも親の rollback に対応する。
 * {@code MatchOnly} / {@code Not} の先読み境界では、成功時にも状態を復元する。
 * 任意の {@code globalScopeTreeMap} の値をコピーする仕組みではない。
 *
 * <h2>生成パーサーからの利用（@scopeTree 付きルール）</h2>
 * <pre>
 * public void onBegin(ParseContext ctx, Parser p) {
 *     ScopeStore.enter(ctx);
 * }
 * public void onCommit(ParseContext ctx, Parser p, TokenList tokens) {
 *     ScopeStore.leave(ctx);
 * }
 * public void onRollback(ParseContext ctx, Parser p, TokenList tokens) {
 *     ScopeStore.leave(ctx);
 * }
 * </pre>
 *
 * <h2>生成パーサーからの利用（@declares 付きルール）</h2>
 * <pre>
 * public void onCommit(ParseContext ctx, Parser p, TokenList tokens) {
 *     ScopeStore.declare(ctx, captureName, sourceOffset);
 * }
 * </pre>
 *
 * <h2>手書きパーサーからの利用例</h2>
 * <pre>
 * // スコープストアを使った変数宣言登録
 * public void onCommit(ParseContext ctx, Parser p, List&lt;Token&gt; tokens) {
 *     String name = extractVariableName(tokens);
 *     ScopeStore.declare(ctx, name, offset);
 * }
 *
 * // スコープを検索して変数が宣言済みか確認
 * boolean declared = ScopeStore.isDeclared(ctx, "$x");
 * Optional&lt;SymbolInfo&gt; info = ScopeStore.resolve(ctx, "$x");
 * </pre>
 */
public final class ScopeStore {

    private ScopeStore() {}

    // =========================================================================
    // Dispatcher registration
    // =========================================================================

    /**
     * 旧 workaround: {@link ParseContext} に ScopeStore ディスパッチャーリスナーを登録する。
     *
     * @deprecated {@link org.unlaxer.context.TransactionListenerContainer} が
     *     begin/commit/rollback の発火時に、parser 自身が {@link TransactionListener} を
     *     実装していれば登録済み listener と同じペイロードで自己通知するようになった
     *     (unlaxer-common 側の根本修正)。このディスパッチャーは不要になり、登録すると
     *     二重通知になるため <b>no-op</b> に変更した。呼び出しは無害なので既存コードは
     *     そのまま動くが、新規コードでは呼ぶ必要はない。
     *
     * @param ctx パース開始前の {@link ParseContext}
     */
    @Deprecated
    public static void registerDispatcher(ParseContext ctx) {
        // no-op: TransactionListenerContainer が自己通知するため転送は不要。
        // 登録すると二重通知になるので何もしない。
    }

    private static final Name STATE_KEY = Name.of(ScopeStore.class, "transactionalState");

    // =========================================================================
    // スコープ管理
    // =========================================================================

    /**
     * スコープを1段深くする（@scopeTree ルールの onBegin から呼ぶ）。
     */
    public static void enter(ParseContext ctx) {
        getStack(ctx).push(new HashMap<>());
        ctx.markMemoizationStateChanged();
    }

    /**
     * スコープを1段浅くする（@scopeTree ルールの onCommit / onRollback から呼ぶ）。
     */
    public static void leave(ParseContext ctx) {
        Deque<Map<String, SymbolInfo>> stack = getStack(ctx);
        if (!stack.isEmpty()) {
            stack.pop();
            ctx.markMemoizationStateChanged();
        }
    }

    /**
     * 現在のスコープの深さを返す（0 = グローバルスコープのみ）。
     */
    public static int currentScopeDepth(ParseContext ctx) {
        return getStack(ctx).size();
    }

    // =========================================================================
    // シンボル管理
    // =========================================================================

    /**
     * 現在スコープにシンボルを登録する（@declares ルールの onCommit から呼ぶ）。
     * スコープスタックが空の場合はグローバルスコープに登録する。
     *
     * @param ctx         パースコンテキスト
     * @param name        シンボル名
     * @param sourceOffset 宣言位置（char offset、go-to-definition 用）
     */
    public static void declare(ParseContext ctx, String name, int sourceOffset) {
        if (name == null || name.isEmpty()) return;
        Deque<Map<String, SymbolInfo>> stack = getStack(ctx);
        Map<String, SymbolInfo> scope = stack.isEmpty() ? getGlobalScope(ctx) : stack.peek();
        SymbolInfo info = new SymbolInfo(name, sourceOffset);
        scope.put(name, info);
        getAllDeclarationsInternal(ctx).add(info);
        ctx.markMemoizationStateChanged();
    }

    /**
     * スコープチェーンを内側から検索してシンボルが宣言済みか確認する。
     *
     * @param ctx  パースコンテキスト
     * @param name シンボル名
     * @return 宣言済みであれば true
     */
    public static boolean isDeclared(ParseContext ctx, String name) {
        return resolve(ctx, name).isPresent();
    }

    /**
     * スコープチェーンを内側から検索してシンボル情報を返す。
     *
     * @param ctx  パースコンテキスト
     * @param name シンボル名
     * @return 見つかれば {@link SymbolInfo}、なければ empty
     */
    public static Optional<SymbolInfo> resolve(ParseContext ctx, String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        // 内側スコープから順に検索
        for (Map<String, SymbolInfo> scope : getStack(ctx)) {
            SymbolInfo info = scope.get(name);
            if (info != null) return Optional.of(info);
        }
        // グローバルスコープも検索
        SymbolInfo info = getGlobalScope(ctx).get(name);
        return Optional.ofNullable(info);
    }

    /**
     * 現在スコープ（最内側）で宣言されたシンボルの一覧を返す。
     */
    public static List<SymbolInfo> declaredInCurrentScope(ParseContext ctx) {
        Deque<Map<String, SymbolInfo>> stack = getStack(ctx);
        if (stack.isEmpty()) return new ArrayList<>(getGlobalScope(ctx).values());
        return new ArrayList<>(stack.peek().values());
    }

    // =========================================================================
    // セマンティック diagnostics
    // =========================================================================

    /**
     * セマンティック診断情報（未定義シンボルなど）を追加する。
     * パース後に {@link #getDiagnostics(ParseContext)} で取得できる。
     *
     * @param ctx      パースコンテキスト
     * @param message  診断メッセージ
     * @param offset   問題箇所の char offset
     * @param length   問題箇所の長さ
     * @param severity {@link Severity}
     */
    public static void addDiagnostic(ParseContext ctx, String message, int offset, int length, Severity severity) {
        getDiagnosticsInternal(ctx).add(new SymbolDiagnostic(message, offset, length, severity));
        ctx.markMemoizationStateChanged();
    }

    /**
     * パース中に蓄積された diagnostics を返す。
     * LSP diagnostics プロバイダや evaluator から呼ぶ。
     * 変更不可のライブビューであり、後続の変更・ロールバックも反映する。
     */
    public static List<SymbolDiagnostic> getDiagnostics(ParseContext ctx) {
        return Collections.unmodifiableList(getDiagnosticsInternal(ctx));
    }

    /** diagnostics リストをクリアする（再パース前など）。 */
    public static void clearDiagnostics(ParseContext ctx) {
        List<SymbolDiagnostic> diagnostics = getDiagnosticsInternal(ctx);
        if (!diagnostics.isEmpty()) {
            diagnostics.clear();
            ctx.markMemoizationStateChanged();
        }
    }

    private static List<SymbolDiagnostic> getDiagnosticsInternal(ParseContext ctx) {
        return state(ctx).diagnostics;
    }

    // =========================================================================
    // 宣言一覧（go-to-definition 用フラットリスト）
    // =========================================================================

    /**
     * パース中に {@link #declare} で登録されたすべての宣言をフラットリストで返す。
     * スコープが閉じた後でも参照可能。
     * LSP の go-to-definition で使用する。
     * 変更不可のライブビューであり、後続の変更・ロールバックも反映する。
     */
    public static List<SymbolInfo> getAllDeclarations(ParseContext ctx) {
        return Collections.unmodifiableList(getAllDeclarationsInternal(ctx));
    }

    private static List<SymbolInfo> getAllDeclarationsInternal(ParseContext ctx) {
        return state(ctx).declarations;
    }

    // =========================================================================
    // 参照一覧（find-references 用フラットリスト）
    // =========================================================================

    /**
     * @backref ルールが参照したシンボルを記録する（生成パーサーの onCommit から呼ぶ）。
     *
     * @param ctx    パースコンテキスト
     * @param name   参照したシンボル名
     * @param offset 参照箇所の char offset
     * @param length 参照箇所の長さ
     */
    public static void addReference(ParseContext ctx, String name, int offset, int length) {
        if (name == null || name.isEmpty()) return;
        getAllReferencesInternal(ctx).add(new ReferenceInfo(name, offset, length));
        ctx.markMemoizationStateChanged();
    }

    /**
     * パース中に記録されたすべての参照をフラットリストで返す。
     * LSP の find-references で使用する。
     * 変更不可のライブビューであり、後続の変更・ロールバックも反映する。
     */
    public static List<ReferenceInfo> getAllReferences(ParseContext ctx) {
        return Collections.unmodifiableList(getAllReferencesInternal(ctx));
    }

    private static List<ReferenceInfo> getAllReferencesInternal(ParseContext ctx) {
        return state(ctx).references;
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private static Deque<Map<String, SymbolInfo>> getStack(ParseContext ctx) {
        return state(ctx).stack;
    }

    private static Map<String, SymbolInfo> getGlobalScope(ParseContext ctx) {
        return state(ctx).global;
    }

    private static State state(ParseContext ctx) {
        return (State) ctx.getGlobalScopeTreeMap().computeIfAbsent(STATE_KEY, key -> {
            State state = new State();
            // Register the empty baseline before the first mutation, including all
            // open parent transactions when first used from a nested parser.
            ctx.registerTransactionalState(state);
            ctx.markMemoizationStateChanged();
            return state;
        });
    }

    private static final class State implements TransactionalState {
        final Deque<Map<String, SymbolInfo>> stack = new ArrayDeque<>();
        final Map<String, SymbolInfo> global = new HashMap<>();
        final List<SymbolInfo> declarations = new ArrayList<>();
        final List<ReferenceInfo> references = new ArrayList<>();
        final List<SymbolDiagnostic> diagnostics = new ArrayList<>();

        @Override public Runnable checkpoint() {
            List<Map<String, SymbolInfo>> savedStack = stack.stream()
                .<Map<String, SymbolInfo>>map(HashMap::new).toList();
            Map<String, SymbolInfo> savedGlobal = new HashMap<>(global);
            List<SymbolInfo> savedDeclarations = List.copyOf(declarations);
            List<ReferenceInfo> savedReferences = List.copyOf(references);
            List<SymbolDiagnostic> savedDiagnostics = List.copyOf(diagnostics);
            return () -> {
                stack.clear();
                savedStack.forEach(scope -> stack.addLast(new HashMap<>(scope)));
                global.clear();
                global.putAll(savedGlobal);
                // Retain list identity so existing unmodifiable live views observe rollback.
                declarations.clear();
                declarations.addAll(savedDeclarations);
                references.clear();
                references.addAll(savedReferences);
                diagnostics.clear();
                diagnostics.addAll(savedDiagnostics);
            };
        }
    }

    // =========================================================================
    // SymbolInfo
    // =========================================================================

    /**
     * スコープに登録されたシンボルの情報。
     *
     * @param name         シンボル名
     * @param sourceOffset 宣言位置（char offset）
     */
    public record SymbolInfo(String name, int sourceOffset) {}

    /**
     * セマンティック診断情報。
     *
     * @param message  診断メッセージ
     * @param offset   問題箇所の char offset
     * @param length   問題箇所の長さ（文字数）
     * @param severity {@link Severity}
     */
    public record SymbolDiagnostic(String message, int offset, int length, Severity severity) {}

    /** 診断の重大度 */
    public enum Severity { ERROR, WARNING, INFO, HINT }

    /**
     * シンボルへの参照情報。
     *
     * @param name   参照したシンボル名
     * @param offset 参照箇所の char offset
     * @param length 参照箇所の長さ（文字数）
     */
    public record ReferenceInfo(String name, int offset, int length) {}
}
