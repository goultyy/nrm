package mt.su.nrm.ssh;

import java.io.IOException;
import java.time.Duration;

/**
 * The raw wire operations. Package-private on purpose: the only way for the rest of the app to
 * reach a server is {@link SshSession}, which logs every call before and after it goes through
 * here.
 */
interface SshTransport extends AutoCloseable {

    /** Raw result: exit status is null if the server didn't send one. */
    record Raw(Integer exitStatus, byte[] stdout, byte[] stderr) {
    }

    Raw exec(String commandLine, byte[] stdin, Duration timeout) throws IOException;

    void upload(byte[] data, String remotePath) throws IOException;

    byte[] download(String remotePath) throws IOException;

    void delete(String remotePath) throws IOException;

    /** Lists a directory (without "." and ".."). */
    java.util.List<SftpEntry> list(String remoteDir) throws IOException;

    /** Resolves a path to its canonical absolute form (e.g. "." to the login directory). */
    String canonicalize(String remotePath) throws IOException;

    void mkdir(String remoteDir) throws IOException;

    /** Creates an empty file. Fails if anything is already at the path: an existing file is never overwritten. */
    void createFile(String remotePath) throws IOException;

    void rename(String from, String to) throws IOException;

    /** Removes an empty directory. */
    void rmdir(String remoteDir) throws IOException;

    /** Streams a local file up, reporting the bytes sent so far. */
    void uploadFile(java.nio.file.Path local, String remotePath, java.util.function.LongConsumer progress)
            throws IOException;

    /** Streams a remote file down, reporting the bytes received so far. */
    void downloadFile(String remotePath, java.nio.file.Path local, java.util.function.LongConsumer progress)
            throws IOException;

    @Override
    void close();

    /** False once the connection has dropped. */
    default boolean isOpen() {
        return true;
    }
}
