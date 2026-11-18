package org.wangshu.ime;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Test-APK-only synthetic editors with native offsets. No disk, clipboard or audio access. */
public final class NativeEditorFixture extends Activity {
    private final ProbeEditor[] editors = new ProbeEditor[2];
    private final JSONArray operations = new JSONArray();
    private TextView metadata;
    private boolean destroyed;
    private boolean pending;
    private long revision;

    @Override public void onCreate(Bundle saved) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(16, 8, 16, 8);
        TextView heading = new TextView(this);
        heading.setText("原生双字段测试 · 仅合成文字 · 状态仅在内存显示");
        column.addView(heading);
        for (int index = 0; index < editors.length; index++) {
            ProbeEditor editor = new ProbeEditor(index);
            editors[index] = editor;
            editor.setId(0x7000 + index);
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            editor.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            editor.setContentDescription("望舒原生合成字段 " + (index == 0 ? "A" : "B"));
            editor.setMinLines(2);
            editor.setMaxLines(4);
            editor.setText(index == 0 ? "合成A｜A👩🏽‍💻éZ｜尾部" : "合成B｜保持不变");
            column.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        }
        metadata = new TextView(this);
        metadata.setTextSize(11);
        metadata.setTextIsSelectable(false);
        column.addView(metadata);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> changed());
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> changed());
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            changed();
            return insets;
        });
        scroll.addView(column);
        setContentView(scroll);
        editors[0].requestFocus();
        changed();
    }

    private void changed() {
        revision++;
        if (metadata == null || pending || destroyed) return;
        pending = true;
        metadata.post(() -> {
            pending = false;
            if (!destroyed) metadata.setText(snapshot().toString());
        });
    }

    /** Instrumentation can read this on the UI thread without screenshots of other windows. */
    public JSONObject snapshot() {
        try {
            JSONObject result = new JSONObject();
            result.put("scope", "synthetic-native-editors");
            result.put("revision", revision);
            result.put("orientation", getResources().getConfiguration().orientation);
            // getLocalVisibleRect describes ancestors. A visible IME may cover area
            // that this method still reports on edge-to-edge hosts.
            int imeTopInWindow = -1;
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    int type = WindowInsets.Type.ime();
                    int bottom = insets.getInsets(type).bottom;
                    int height = getWindowManager().getCurrentWindowMetrics().getBounds().height();
                    boolean shown = insets.isVisible(type);
                    result.put("imeBottomInsetPx", bottom).put("imeVisible", shown)
                            .put("windowHeightPx", height);
                    if (shown && bottom > 0 && bottom <= height) {
                        imeTopInWindow = height - bottom;
                        result.put("imeTopInWindowPx", imeTopInWindow);
                    }
                }
            }
            JSONArray fields = new JSONArray();
            for (ProbeEditor editor : editors) {
                if (editor == null) continue;
                Editable text = editor.getText();
                Rect visible = new Rect();
                int visibleHeight = editor.getLocalVisibleRect(visible) ? visible.height() : 0;
                JSONObject field = new JSONObject().put("field", editor.index)
                        .put("text", text.toString()).put("selectionStart", editor.getSelectionStart())
                        .put("selectionEnd", editor.getSelectionEnd())
                        .put("composingStart", BaseInputConnection.getComposingSpanStart(text))
                        .put("composingEnd", BaseInputConnection.getComposingSpanEnd(text))
                        .put("focused", editor.hasFocus()).put("connectionGeneration", editor.generation)
                        .put("visibleHeightPx", visibleHeight).put("lineHeightPx", editor.getLineHeight());
                if (imeTopInWindow >= 0) {
                    int[] origin = new int[2];
                    editor.getLocationInWindow(origin);
                    // Convert the local, scrolled drawing coordinates to view bounds.
                    visible.offset(-editor.getScrollX(), -editor.getScrollY());
                    visible.bottom = Math.min(visible.bottom, imeTopInWindow - origin[1]);
                    int unoccludedHeight = visibleHeight > 0 ? Math.max(0, visible.height()) : 0;
                    field.put("unoccludedHeightPx", unoccludedHeight).put("unoccludedTextLines",
                            unoccludedHeight > 0 ? countVisibleTextLines(editor, visible) : 0);
                }
                fields.put(field);
            }
            result.put("fields", fields);
            result.put("operations", new JSONArray(operations.toString()));
            return result;
        } catch (JSONException malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    private static int countVisibleTextLines(EditText editor, Rect clip) {
        Layout layout = editor.getLayout();
        if (layout == null) return 0;
        if (!clip.intersect(editor.getCompoundPaddingLeft(), editor.getExtendedPaddingTop(),
                editor.getWidth() - editor.getCompoundPaddingRight(),
                editor.getHeight() - editor.getExtendedPaddingBottom())) return 0;
        int x = editor.getCompoundPaddingLeft() - editor.getScrollX();
        int y = editor.getTotalPaddingTop() - editor.getScrollY();
        int lines = 0;
        for (int line = 0; line < layout.getLineCount(); line++) {
            if (layout.getLineVisibleEnd(line) > layout.getLineStart(line)
                    && y + layout.getLineTop(line) >= clip.top
                    && y + layout.getLineBottom(line) <= clip.bottom
                    && x + layout.getLineLeft(line) >= clip.left
                    && x + layout.getLineRight(line) <= clip.right) {
                lines++;
            }
        }
        return lines;
    }

    private void observed(int field, int generation, String operation, boolean accepted) {
        try {
            if (operations.length() == 64) operations.remove(0);
            operations.put(new JSONObject().put("field", field).put("generation", generation)
                    .put("operation", operation).put("accepted", accepted));
            changed();
        } catch (JSONException malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private final class ProbeEditor extends EditText {
        final int index;
        int generation;

        ProbeEditor(int index) {
            super(NativeEditorFixture.this);
            this.index = index;
            setOnFocusChangeListener((view, focused) -> changed());
            addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) { changed(); }
                public void afterTextChanged(Editable text) { }
            });
        }

        @Override protected void onSelectionChanged(int start, int end) {
            super.onSelectionChanged(start, end);
            changed();
        }

        @Override public InputConnection onCreateInputConnection(EditorInfo info) {
            InputConnection connection = super.onCreateInputConnection(info);
            if (connection == null) return null;
            final int owner = ++generation;
            changed();
            return new InputConnectionWrapper(connection, false) {
                @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
                    ExtractedText value = super.getExtractedText(request, flags);
                    observed(index, owner, "getExtractedText", value != null);
                    return value;
                }
                @Override public boolean setComposingText(CharSequence text, int position) {
                    boolean accepted = super.setComposingText(text, position);
                    observed(index, owner, "setComposingText", accepted);
                    return accepted;
                }
                @Override public boolean finishComposingText() {
                    boolean accepted = super.finishComposingText();
                    observed(index, owner, "finishComposingText", accepted);
                    return accepted;
                }
                @Override public boolean commitText(CharSequence text, int position) {
                    boolean accepted = super.commitText(text, position);
                    observed(index, owner, "commitText", accepted);
                    return accepted;
                }
                @Override public boolean deleteSurroundingText(int before, int after) {
                    boolean accepted = super.deleteSurroundingText(before, after);
                    observed(index, owner, "deleteSurroundingText", accepted);
                    return accepted;
                }
                @Override public boolean setSelection(int start, int end) {
                    boolean accepted = super.setSelection(start, end);
                    observed(index, owner, "setSelection", accepted);
                    return accepted;
                }
            };
        }
    }
}
