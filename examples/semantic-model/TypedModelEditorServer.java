package example.semantic;

import org.unlaxer.dsl.semantic.EditorParseResult;
import org.unlaxer.editor.EditorCst;

/** Generated LSP server consumes the same model-specific partial parser as the playground. */
public final class TypedModelEditorServer extends TypedModelLanguageServer {
    @Override protected EditorParseResult<?> editorParseResult(String uri, long version, String source) {
        return TypedModelEditor.parse(uri, version, source, null, EditorCst.Options.defaults()).result();
    }
}
