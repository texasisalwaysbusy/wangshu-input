package org.wangshu.ime;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/** Explicit recovery of the authored dictionary and phrases from a blocked store. */
final class PersonalRecoveryFiles {
    private static final String[] SUFFIXES = {"", ".bak", ".new", ".pending"};
    private static final int BASE = 0, BACKUP = 1, STAGED = 2, PENDING = 3;
    private static final int MAX_EVIDENCE_BYTES = 16 * PersonalStore.MAX_BYTES;
    private static final Set<Path> ACTIVE_LOCKS = new HashSet<>();

    private PersonalRecoveryFiles() {}

    static final class Lock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final Path path;

        private Lock(FileChannel channel, FileLock lock, Path path) {
            this.channel = channel;
            this.lock = lock;
            this.path = path;
        }

        @Override public void close() throws IOException {
            try {
                lock.release();
            } finally {
                try {
                    channel.close();
                } finally {
                    releasePath(path);
                }
            }
        }
    }

    private static void releasePath(Path path) {
        synchronized (ACTIVE_LOCKS) {
            ACTIVE_LOCKS.remove(path);
        }
    }

    private static IOException busy() {
        return new IOException("个人数据正在写入，请稍后重新打开或预览。");
    }

    static Lock lock(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        Path target = absolute.getParentFile().getCanonicalFile().toPath().resolve(absolute.getName());
        Path path = sidecar(target, ".lock");
        // Avoid opening a second descriptor in this process while its OS lock is active.
        synchronized (ACTIVE_LOCKS) {
            if (!ACTIVE_LOCKS.add(path)) throw busy();
        }
        FileChannel channel = null;
        boolean acquired = false;
        try {
            channel = FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock();
            if (lock == null) throw busy();
            acquired = true;
            return new Lock(channel, lock, path);
        } catch (OverlappingFileLockException busy) {
            throw busy();
        } finally {
            if (!acquired) {
                try {
                    if (channel != null) channel.close();
                } finally {
                    releasePath(path);
                }
            }
        }
    }

    private static Path sidecar(Path file, String suffix) {
        return file.resolveSibling(file.getFileName().toString() + suffix);
    }

    private static byte[] readOriginal(Path path, int remaining) throws IOException {
        BasicFileAttributes before;
        try {
            before = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
        if (!before.isRegularFile() || before.size() > remaining) {
            throw new IOException("无法安全保留个人数据故障原件，请检查本机文件；文件未替换。");
        }
        try (InputStream input = Channels.newInputStream(FileChannel.open(path, READ, NOFOLLOW_LINKS))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) before.size());
            byte[] block = new byte[8192];
            int count;
            while ((count = input.read(block)) != -1) {
                if (count > remaining - output.size()) throw new PersonalStore.StaleDataException();
                output.write(block, 0, count);
            }
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!after.isRegularFile() || after.size() != output.size() || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey())) {
                throw new PersonalStore.StaleDataException();
            }
            return output.toByteArray();
        }
    }

    private static byte[][] originals(Path file) throws IOException {
        int remaining = MAX_EVIDENCE_BYTES;
        byte[][] result = new byte[SUFFIXES.length][];
        for (int i = 0; i < SUFFIXES.length; i++) {
            result[i] = readOriginal(sidecar(file, SUFFIXES[i]), remaining);
            if (result[i] != null) remaining -= result[i].length;
        }
        return result;
    }

    private static JSONObject readable(byte[] original) {
        if (original == null) return null;
        try {
            return PersonalStore.parse(original);
        } catch (IOException invalid) {
            return null;
        }
    }

    static final class Plan {
        private final Path file;
        private final byte[][] originals;
        private final byte[] payload;
        final String source;
        final int words, phrases;

        private Plan(Path file, byte[][] originals, JSONObject selected, String source) throws IOException {
            this.file = file;
            this.originals = originals;
            this.payload = PersonalStore.bytes(selected);
            this.source = source;
            this.words = selected.optJSONArray("words").length();
            this.phrases = selected.optJSONArray("phrases").length();
        }

        JSONObject data() throws IOException {
            return PersonalStore.parse(payload);
        }
    }

    static Plan prepare(File target, byte[] external) throws IOException {
        Path file = target.toPath().toAbsolutePath().normalize();
        byte[][] originals = originals(file);
        JSONObject primary = readable(originals[BASE]);
        JSONObject backup = readable(originals[BACKUP]);
        JSONObject effective = originals[BACKUP] == null ? primary : backup;
        boolean missing = originals[BASE] == null && originals[BACKUP] == null && originals[STAGED] == null;
        if (originals[PENDING] == null && (effective != null || missing)) {
            throw new IOException("当前个人数据可读。请使用合并导入，保留已有词库与常用语。");
        }
        if (external != null) {
            PersonalStore.ImportData parsed = PersonalStore.importFile(external);
            if (!"JSON".equals(parsed.format)) throw new IOException("故障恢复需要词库与常用语 JSON v1 备份。");
            return new Plan(file, originals, parsed.data, "所选 JSON 备份");
        }
        if (backup != null) return new Plan(file, originals, backup, "本机上次保留的备份");
        if (primary != null) return new Plan(file, originals, primary, "本机当前文件");
        throw new IOException("本机没有可读的已保存副本，请选择词库与常用语 JSON 备份。");
    }

    static File restore(File target, Plan plan) throws IOException {
        Path file = target.toPath().toAbsolutePath().normalize();
        if (plan == null || !file.equals(plan.file)) throw new PersonalStore.StaleDataException();
        Path parent = file.getParent();
        try (Lock ignored = lock(target)) {
            if (!Arrays.deepEquals(plan.originals, originals(file))) throw new PersonalStore.StaleDataException();

            // Preserve every exact original before changing any live file.
            Path archive = Files.createTempDirectory(parent, "personal-fault-");
            for (int i = 0; i < SUFFIXES.length; i++) {
                if (plan.originals[i] != null) {
                    Path copy = archive.resolve(sidecar(file, SUFFIXES[i]).getFileName());
                    SettingsRecoveryFiles.writeNew(copy, plan.originals[i]);
                    if (!Arrays.equals(plan.originals[i], readOriginal(copy, plan.originals[i].length))) {
                        throw new IOException("故障原件备份无法确认，原文件未替换。");
                    }
                }
            }
            Path replacement = archive.resolve("restore.tmp");
            SettingsRecoveryFiles.writeNew(replacement, plan.payload);
            SettingsRecoveryFiles.forceDirectory(archive);
            SettingsRecoveryFiles.forceDirectory(parent);
            if (!Arrays.deepEquals(plan.originals, originals(file))) throw new PersonalStore.StaleDataException();

            Path pending = sidecar(file, SUFFIXES[PENDING]);
            if (plan.originals[PENDING] == null) SettingsRecoveryFiles.writeNew(pending, new byte[]{1});
            SettingsRecoveryFiles.forceDirectory(parent);
            for (int i : new int[]{BACKUP, STAGED}) {
                if (plan.originals[i] != null) Files.delete(sidecar(file, SUFFIXES[i]));
            }
            Files.move(replacement, file, ATOMIC_MOVE, REPLACE_EXISTING);
            if (!Arrays.equals(plan.payload, readOriginal(file, PersonalStore.MAX_BYTES))) {
                throw new PersonalStore.UnconfirmedSaveException();
            }
            SettingsRecoveryFiles.forceDirectory(archive);
            SettingsRecoveryFiles.forceDirectory(parent);
            // A reboot before this unlink persists can only cause another blocked recovery.
            Files.delete(pending);
            return archive.toFile();
        }
    }
}
