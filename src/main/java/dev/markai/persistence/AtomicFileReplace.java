package dev.markai.persistence;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Replace a world-save file without deleting the previous version if Windows briefly locks it. */
public final class AtomicFileReplace {
    @FunctionalInterface public interface MoveOperation {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    private AtomicFileReplace() { }

    public static void replace(Path temporary, Path target, MoveOperation operation) throws IOException {
        final int retries = 5;
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    operation.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    operation.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException blocked) {
                if (attempt == retries) throw blocked;
                try {
                    Thread.sleep(10L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException failure = new IOException("Interrupted while replacing world save", interrupted);
                    failure.addSuppressed(blocked);
                    throw failure;
                }
            }
        }
    }
}
