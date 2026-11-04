package org.wangshu.ime;

import java.nio.file.*;
import java.util.Locale;

/** A capability observation, not Android or power-loss acceptance. Never changes production sync. */
public final class SettingsHostFilesystemProbe {
    public static void main(String[] args)throws Exception {
        Path directory=Files.createDirectory(Path.of(args[0]));
        // Report the Windows directory-sync AccessDenied observation only after ordinary file I/O/force works.
        SettingsRecoveryFiles.writeNew(directory.resolve("file-sync-probe"),new byte[]{1,2,3});
        try {
            SettingsRecoveryFiles.forceDirectory(directory);
            System.out.println("HOST_DIRECTORY_SYNC=AVAILABLE");
        } catch(AccessDeniedException unavailable) {
            if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows"))throw unavailable;
            System.out.println("HOST_DIRECTORY_SYNC=WINDOWS_DIRECTORY_SYNC_ACCESS_DENIED");
            unavailable.printStackTrace(System.out);
        }
        System.out.println("Android storage and power-loss durability NOT_RUN.");
    }
}
