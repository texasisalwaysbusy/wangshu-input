package org.wangshu.ime;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** HOST TEST ONLY. Explicit success/failure acknowledgement model, NEVER durable I/O. */
final class HostDirectorySyncModel {
    static int calls;
    static final Map<Path,Set<String>> acknowledgedNames=new HashMap<>();
    static void force(Path directory)throws IOException {
        calls++;
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("synthetic sync target is not a directory");
        String outcome=System.getProperty("wangshu.hostTest.directorySync","");
        if(outcome.equals("fail"))throw new IOException("HOST TEST injected directory-sync failure; no durability claim");
        if(!outcome.equals("acknowledge"))throw new IOException("HOST TEST sync model requires an explicit outcome");
        Set<String> names=new TreeSet<>();
        try(DirectoryStream<Path> listing=Files.newDirectoryStream(directory)) {
            for(Path entry:listing)names.add(entry.getFileName().toString());
        }
        acknowledgedNames.put(directory,Collections.unmodifiableSet(names));
    }
    private HostDirectorySyncModel(){}
}
