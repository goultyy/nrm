package mt.su.nrm.config;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.util.Secrets;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stores server profiles in one encrypted file (envelope encryption).
 * <p>
 * Every save generates a fresh AES-256 data key, encrypts the profiles with AES-GCM, and stores
 * the data key wrapped by a {@link KeyProtector} (DPAPI on Windows). The header is authenticated
 * as GCM associated data, so any change to the file is detected.
 * <p>
 * File layout: "NRMP" | format (int) | protector id | wrapped key | nonce (12) | ciphertext+tag.
 * <p>
 * Writes go to a temp file that is flushed to disk and then atomically moved into place. The
 * previous file is kept as {@code <name>.bak}.
 */
public final class EncryptedProfileStore {

    static final int FILE_FORMAT = 1;

    private static final byte[] MAGIC = {'N', 'R', 'M', 'P'};
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path file;
    private final KeyProtector protector;

    public EncryptedProfileStore(Path file, KeyProtector protector) {
        this.file = file.toAbsolutePath();
        this.protector = protector;
    }

    public Path file() {
        return file;
    }

    public Path backupFile() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    public boolean hasBackup() {
        return Files.isRegularFile(backupFile());
    }

    /** Loads all profiles. A missing file means no profiles yet. */
    public synchronized List<ServerProfile> load() throws ProfileStoreException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        return read(file);
    }

    /** Loads the previous version kept as a backup. */
    public synchronized List<ServerProfile> loadBackup() throws ProfileStoreException {
        Path backup = backupFile();
        if (!Files.exists(backup)) {
            throw new ProfileStoreException(ProfileStoreException.Kind.IO, "There is no backup to restore.");
        }
        return read(backup);
    }

    public synchronized void save(List<ServerProfile> profiles) throws ProfileStoreException {
        byte[] plaintext = null;
        byte[] key = null;
        try {
            plaintext = ProfileCodec.encode(profiles);
            key = new byte[KEY_BYTES];
            RANDOM.nextBytes(key);
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);

            byte[] header = header(protector.id(), protector.protect(key), nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] ciphertext = cipher.doFinal(plaintext);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream(header.length + ciphertext.length + 4);
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(header);
            BinaryIO.writeBytes(out, ciphertext);
            out.flush();

            writeAtomically(bytes.toByteArray());
        } catch (GeneralSecurityException e) {
            throw new ProfileStoreException(ProfileStoreException.Kind.CANNOT_UNLOCK,
                    "Could not encrypt server profiles: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ProfileStoreException(ProfileStoreException.Kind.IO,
                    "Could not save server profiles to " + file + ": " + e.getMessage(), e);
        } finally {
            Secrets.wipe(plaintext);
            Secrets.wipe(key);
        }
    }

    /**
     * Moves a file that can't be read out of the way (to {@code <name>.damaged-<timestamp>}) so a
     * fresh start or a restored backup never overwrites it.
     *
     * @return where the file was moved, or null if there was no file
     */
    public synchronized Path quarantineDamagedFile() throws ProfileStoreException {
        if (!Files.exists(file)) {
            return null;
        }
        Path target = file.resolveSibling(file.getFileName() + ".damaged-" + STAMP.format(Instant.now()));
        try {
            return Files.move(file, target);
        } catch (IOException e) {
            throw new ProfileStoreException(ProfileStoreException.Kind.IO,
                    "Could not move the damaged profile store aside: " + e.getMessage(), e);
        }
    }

    private static byte[] header(String protectorId, byte[] wrappedKey, byte[] nonce) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeInt(FILE_FORMAT);
        BinaryIO.writeString(out, protectorId);
        BinaryIO.writeBytes(out, wrappedKey);
        out.write(nonce);
        out.flush();
        return bytes.toByteArray();
    }

    private List<ServerProfile> read(Path path) throws ProfileStoreException {
        byte[] all;
        try {
            all = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ProfileStoreException(ProfileStoreException.Kind.IO,
                    "Could not read " + path + ": " + e.getMessage(), e);
        }

        String protectorId;
        byte[] wrappedKey;
        byte[] nonce;
        byte[] header;
        byte[] ciphertext;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(all));
            if (!Arrays.equals(in.readNBytes(MAGIC.length), MAGIC)) {
                throw damaged(path, "it is not a profile store file", null);
            }
            int format = in.readInt();
            if (format > FILE_FORMAT) {
                throw new ProfileStoreException(ProfileStoreException.Kind.UNSUPPORTED_VERSION,
                        "The profile store was saved by a newer version of NRM.");
            }
            if (format != FILE_FORMAT) {
                throw damaged(path, "unknown format " + format, null);
            }
            protectorId = BinaryIO.readString(in);
            wrappedKey = BinaryIO.readBytes(in);
            nonce = in.readNBytes(NONCE_BYTES);
            if (nonce.length != NONCE_BYTES) {
                throw new EOFException();
            }
            header = Arrays.copyOf(all, all.length - in.available());
            ciphertext = BinaryIO.readBytes(in);
            if (in.available() != 0) {
                throw damaged(path, "unexpected data at the end of the file", null);
            }
        } catch (IOException e) {
            throw damaged(path, "the file is truncated or malformed", e);
        }

        if (!protectorId.equals(protector.id())) {
            throw new ProfileStoreException(ProfileStoreException.Kind.PROTECTOR_MISMATCH,
                    "The profile store is protected with '" + protectorId + "', but the app is using '"
                            + protector.id() + "'.");
        }

        byte[] key = null;
        byte[] plaintext = null;
        try {
            try {
                key = protector.unprotect(wrappedKey);
            } catch (GeneralSecurityException e) {
                throw new ProfileStoreException(ProfileStoreException.Kind.CANNOT_UNLOCK,
                        "The profile store could not be unlocked. It may belong to a different Windows user"
                                + " or computer, or the passphrase is wrong.", e);
            }
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
                cipher.updateAAD(header);
                plaintext = cipher.doFinal(ciphertext);
            } catch (AEADBadTagException e) {
                throw damaged(path, "its contents failed the integrity check", e);
            } catch (GeneralSecurityException e) {
                throw damaged(path, e.getMessage(), e);
            }
            try {
                return ProfileCodec.decode(plaintext);
            } catch (IOException e) {
                throw damaged(path, e.getMessage(), e);
            }
        } finally {
            Secrets.wipe(key);
            Secrets.wipe(plaintext);
        }
    }

    private static ProfileStoreException damaged(Path path, String reason, Throwable cause) {
        return new ProfileStoreException(ProfileStoreException.Kind.DAMAGED,
                "The profile store " + path.getFileName() + " is damaged: " + reason + ".", cause);
    }

    private void writeAtomically(byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        if (Files.exists(file)) {
            Files.copy(file, backupFile(), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
