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
import org.unlaxer.context.ParseContext;

/**
 * UBNF の {@code @scopeTree} / {@code @declares} / {@code @backref} アノテーションが
 * 使用するランタイムスコープストア。
 *
 * <p>レキシカルスコープのネスト管理と、スコープ内のシンボル宣言・参照解決を担う。
 * {@link ParseContext#getGlobalScopeTreeMap()} を介してパースコンテキストに紐付けられる。
 *
 * <p>スコープスタックは {@code ParseContext} のスナップショット機構と連携する。
 * {@code enter()} / {@code leave()} / {@code declare()} の変更は
 * パーサーのロールバック時に自動的に巻き戻される。
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

    /** globalScopeTreeMap のキー（スコープスタック） */
    private static final Name SCOPE_STACK_KEY = Name.of(ScopeStore.class, "scopeStack");

    // =========================================================================
    // スコープ管理
    // =========================================================================

    /**
     * スコープを1段深くする（@scopeTree ルールの onBegin から呼ぶ）。
     */
    public static void enter(ParseContext ctx) {
        getStack(ctx).push(new HashMap<>());
    }

    /**
     * スコープを1段浅くする（@scopeTree ルールの onCommit / onRollback から呼ぶ）。
     */
    public static void leave(ParseContext ctx) {
        Deque<Map<String, SymbolInfo>> stack = getStack(ctx);
        if (!stack.isEmpty()) {
            stack.pop();
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
        scope.put(name, new SymbolInfo(name, sourceOffset));
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

    private static final Name DIAGNOSTICS_KEY = Name.of(ScopeStore.class, "diagnostics");

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
    }

    /**
     * パース中に蓄積された diagnostics を返す。
     * LSP diagnostics プロバイダや evaluator から呼ぶ。
     */
    public static List<SymbolDiagnostic> getDiagnostics(ParseContext ctx) {
        return Collections.unmodifiableList(getDiagnosticsInternal(ctx));
    }

    /** diagnostics リストをクリアする（再パース前など）。 */
    public static void clearDiagnostics(ParseContext ctx) {
        getDiagnosticsInternal(ctx).clear();
    }

    @SuppressWarnings("unchecked")
    private static List<SymbolDiagnostic> getDiagnosticsInternal(ParseContext ctx) {
        return (List<SymbolDiagnostic>) ctx.getGlobalScopeTreeMap()
            .computeIfAbsent(DIAGNOSTICS_KEY, k -> new ArrayList<>());
    }

    // =========================================================================
    // 内部
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static Deque<Map<String, SymbolInfo>> getStack(ParseContext ctx) {
        // globalScopeTreeMap は ParseContext.Snapshot によりロールバック時に復元される。
        // Deque 自体の参照はスナップショット時に浅いコピーされるが、
        // enter/leave は新しい HashMap を push/pop するだけなのでスナップショット整合性を保てる。
        return (Deque<Map<String, SymbolInfo>>) ctx.getGlobalScopeTreeMap()
            .computeIfAbsent(SCOPE_STACK_KEY, k -> new ArrayDeque<>());
    }

    private static final Name GLOBAL_SCOPE_KEY = Name.of(ScopeStore.class, "globalScope");

    @SuppressWarnings("unchecked")
    private static Map<String, SymbolInfo> getGlobalScope(ParseContext ctx) {
        return (Map<String, SymbolInfo>) ctx.getGlobalScopeTreeMap()
            .computeIfAbsent(GLOBAL_SCOPE_KEY, k -> new HashMap<>());
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
}
