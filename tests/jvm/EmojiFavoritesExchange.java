package org.wangshu.ime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercise the production authored-data store on disposable files. */
public final class EmojiFavoritesExchange {
    private static final String FACE = "👩🏽‍💻";
    private static final String KAOMOJI = "( 'ω' )";
    private static int checks;

    private static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        System.out.println("PASS " + label);
        checks++;
    }

    interface IoAction { void run() throws Exception; }
    private static void rejects(IoAction action, String label) throws Exception {
        try { action.run(); }
        catch (IOException refused) { check(true, label); return; }
        throw new AssertionError(label);
    }

    private static void write(Path directory) throws Exception {
        Path file = directory.resolve("store.json");
        PersonalStore store = new PersonalStore(file.toFile());
        store.putWord(-1, "saved", "合成词");
        store.putPhrase(-1, "合成常用语\nα", "demo");
        byte[] legacy = store.export();
        check(PersonalStore.parse(legacy).getInt("version") == 1
                && store.favoriteSnapshot().glyphs.isEmpty(), "v1 opens with no favorites");
        long initial = store.favoriteSnapshot().revision;
        store.setFavorite(FACE, true, initial);
        rejects(() -> store.setFavorite(KAOMOJI, true, initial), "a stale action cannot change favorites");
        store.setFavorite(KAOMOJI, true, store.favoriteSnapshot().revision);
        long pinned = store.favoriteSnapshot().revision;
        byte[] complete = store.export();
        store.setFavorite(FACE, true, pinned);
        check(pinned == store.favoriteSnapshot().revision && Arrays.equals(complete, store.export()),
                "repeat pin leaves revision and bytes unchanged");
        check(Arrays.equals(legacy, store.exportLegacy()), "legacy copy keeps the exact v1 words and phrases");
        PersonalStore reopened = new PersonalStore(file.toFile());
        check(new ArrayList<>(reopened.favoriteSnapshot().glyphs).equals(Arrays.asList(FACE, KAOMOJI)),
                "reopen preserves order and exact ZWJ / kaomoji bytes");
        PersonalStore.ImportPreview duplicate = reopened.prepareImport(PersonalStore.parse(complete));
        check(duplicate.favoriteCount == 2, "complete import previews deduplication");
        reopened.confirmImport(duplicate);
        reopened.merge(PersonalStore.parse(legacy));
        check(Arrays.equals(complete, reopened.export()), "v1 merge retains favorites");
        long revision = reopened.favoriteSnapshot().revision;
        rejects(() -> reopened.setFavorite("arbitrary text", true, revision), "catalog cannot store arbitrary text as a favorite");
        check(Arrays.equals(complete, reopened.export()), "invalid pin preserves the file");
        JSONObject invalid = PersonalStore.parse(complete);
        invalid.put("favorites", new JSONArray().put(42));
        rejects(() -> reopened.merge(invalid), "non-string favorite is rejected before merge");
        invalid.put("version", 1);
        rejects(() -> reopened.merge(invalid), "v1 still rejects an unknown favorites field");
        JSONObject capacity = PersonalStore.parse(complete);
        JSONArray list = new JSONArray();
        for (EmojiCatalog.Item item : EmojiCatalog.items().subList(0, PersonalStore.MAX_FAVORITES))
            list.put(item.text);
        capacity.put("favorites", list);
        rejects(() -> reopened.prepareImport(capacity), "union above 64 is rejected in preview");
        check(Arrays.equals(complete, reopened.export()), "capacity failure preserves prior bytes");
        Files.write(directory.resolve("android-v2.json"), complete);
        Files.write(directory.resolve("android-v1.json"), legacy);

        Path damaged = directory.resolve("damaged.json");
        Files.write(damaged, new byte[]{'{','x'});
        PersonalRecoveryFiles.Plan plan = PersonalRecoveryFiles.prepare(damaged.toFile(), complete);
        check(plan.favorites == 2, "repair preview counts favorites");
        PersonalRecoveryFiles.restore(damaged.toFile(), plan);
        check(Arrays.equals(complete, new PersonalStore(damaged.toFile()).export()), "repair restores favorites with words and phrases");
    }

    private static void read(Path directory, Path imported) throws Exception {
        PersonalStore restored = new PersonalStore(directory.resolve("restored.json").toFile());
        restored.confirmImport(restored.prepareImport(PersonalStore.parseImport(Files.readAllBytes(imported))));
        check(new ArrayList<>(restored.favoriteSnapshot().glyphs).equals(Arrays.asList(FACE, "🇨🇦")),
                "Windows export restores order and exact glyphs on Android");
        check(restored.words("saved")[0].equals("合成词") && restored.words("demo")[0].equals("合成常用语\nα"),
                "Windows exchange keeps the authored word and phrase");
        for (String glyph : restored.favoriteSnapshot().glyphs)
            restored.setFavorite(glyph, false, restored.favoriteSnapshot().revision);
        JSONObject legacy = PersonalStore.parse(restored.export());
        check(legacy.getInt("version") == 1 && !legacy.has("favorites") && legacy.getJSONArray("phrases").length() == 1,
                "removing the last favorite returns to v1 without losing other data");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 2) throw new IllegalArgumentException("new directory [Windows export]");
        Path directory = Files.createDirectory(Paths.get(args[0]));
        if (args.length == 1) write(directory);
        else read(directory, Paths.get(args[1]));
        System.out.println("TOTAL " + checks + " PASS; synthetic authored-data files");
    }
}
