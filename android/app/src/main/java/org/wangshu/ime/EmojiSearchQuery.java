package org.wangshu.ime;

import java.util.Collections;
import java.util.List;

/** Query and result ownership for one open picker. It never accesses an editor. */
final class EmojiSearchQuery {
    private final EmojiCatalog.Kind kind;
    private String text = "";
    private List<EmojiCatalog.Item> results = Collections.emptyList();

    EmojiSearchQuery(EmojiCatalog.Kind kind) {
        this.kind = kind;
    }

    String text() { return text; }
    List<EmojiCatalog.Item> results() { return results; }

    boolean append(int letter) {
        if (letter >= 'A' && letter <= 'Z') letter += 'a' - 'A';
        if (letter < 'a' || letter > 'z' || text.length() >= EmojiCatalog.PINYIN_LIMIT)
            return false;
        replace(text + (char) letter);
        return true;
    }

    boolean backspace() {
        if (text.isEmpty()) return false;
        replace(text.substring(0, text.length() - 1));
        return true;
    }

    boolean selectable(List<EmojiCatalog.Item> displayed, EmojiCatalog.Item item) {
        return displayed == results && displayed.contains(item);
    }

    void reset() {
        text = "";
        results = Collections.emptyList();
    }

    private void replace(String value) {
        text = value;
        results = EmojiCatalog.searchPinyin(text, kind);
    }
}
