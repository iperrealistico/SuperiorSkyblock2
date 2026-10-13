package com.bgsoftware.superiorskyblock.pvc;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class PvcAtomicFiles {
    private PvcAtomicFiles() { }
    public static void replace(Path target, byte[] content, String backupSuffix) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, content);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temporary,
                    java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            if (Files.exists(target)) Files.copy(target, target.resolveSibling(target.getFileName() + backupSuffix),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                // Failing closed preserves the original rather than exposing a partially written policy/config.
                throw new IOException("The filesystem does not support atomic policy/config replacement", ex);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
