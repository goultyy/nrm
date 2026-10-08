package mt.su.nrm.ssh;

/**
 * One item in a remote directory listing.
 *
 * @param modified seconds since the epoch
 */
public record SftpEntry(String name, boolean directory, long size, long modified, String permissions) {
}
