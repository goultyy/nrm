package mt.su.nrm.config;

import mt.su.nrm.model.ServerProfile;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The app's view of saved server profiles. Every change is validated and written to the
 * encrypted store before it takes effect; if the write fails, nothing changes in memory.
 * <p>
 * Profiles going in and out are copies, so callers can edit freely without affecting saved state.
 */
public final class ProfileRepository {

    private final EncryptedProfileStore store;
    private final Clock clock;
    private final List<ServerProfile> profiles;

    private ProfileRepository(EncryptedProfileStore store, Clock clock, List<ServerProfile> loaded) {
        this.store = store;
        this.clock = clock;
        this.profiles = new ArrayList<>(loaded);
    }

    public static ProfileRepository open(EncryptedProfileStore store) throws ProfileStoreException {
        return open(store, Clock.systemUTC());
    }

    public static ProfileRepository open(EncryptedProfileStore store, Clock clock) throws ProfileStoreException {
        return new ProfileRepository(store, clock, store.load());
    }

    /**
     * Recovers after {@link ProfileStoreException.Kind#DAMAGED}: moves the damaged file aside and
     * makes the backup the current store.
     */
    public static ProfileRepository recoverFromBackup(EncryptedProfileStore store, Clock clock)
            throws ProfileStoreException {
        List<ServerProfile> recovered = store.loadBackup();
        store.quarantineDamagedFile();
        store.save(recovered);
        return new ProfileRepository(store, clock, recovered);
    }

    /**
     * Starts with no profiles after the store couldn't be opened. The unreadable file is moved
     * aside, never deleted.
     */
    public static ProfileRepository startFresh(EncryptedProfileStore store, Clock clock)
            throws ProfileStoreException {
        store.quarantineDamagedFile();
        return new ProfileRepository(store, clock, List.of());
    }

    public EncryptedProfileStore store() {
        return store;
    }

    /** All profiles sorted by name. */
    public synchronized List<ServerProfile> list() {
        return profiles.stream()
                .sorted(Comparator.comparing(ServerProfile::getName, String.CASE_INSENSITIVE_ORDER))
                .map(ServerProfile::copy)
                .toList();
    }

    public synchronized Optional<ServerProfile> find(UUID id) {
        int index = indexOf(id);
        return index < 0 ? Optional.empty() : Optional.of(profiles.get(index).copy());
    }

    /** Validates and saves a new profile, assigning an id if it has none. Returns the saved copy. */
    public synchronized ServerProfile add(ServerProfile draft) throws ProfileStoreException {
        ServerProfile profile = normalized(draft);
        if (profile.getId() == null) {
            profile.setId(UUID.randomUUID());
        } else if (indexOf(profile.getId()) >= 0) {
            throw new IllegalArgumentException("A profile with id " + profile.getId() + " already exists.");
        }
        validate(profile);
        Instant now = clock.instant();
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        commit(list -> list.add(profile));
        return profile.copy();
    }

    /** Validates and saves changes to an existing profile. Returns the saved copy. */
    public synchronized ServerProfile update(ServerProfile edited) throws ProfileStoreException {
        int index = indexOf(edited.getId());
        if (index < 0) {
            throw new NoSuchElementException("No profile with id " + edited.getId() + ".");
        }
        ServerProfile profile = normalized(edited);
        validate(profile);
        profile.setCreatedAt(profiles.get(index).getCreatedAt());
        profile.setUpdatedAt(clock.instant());
        commit(list -> list.set(index, profile));
        return profile.copy();
    }

    /** Returns false if there was no such profile. */
    public synchronized boolean delete(UUID id) throws ProfileStoreException {
        int index = indexOf(id);
        if (index < 0) {
            return false;
        }
        commit(list -> list.remove(index));
        return true;
    }

    private static ServerProfile normalized(ServerProfile source) {
        ServerProfile p = source.copy();
        p.setName(p.getName().strip());
        p.setHost(p.getHost().strip());
        p.setUsername(p.getUsername().strip());
        return p;
    }

    private void validate(ServerProfile profile) {
        List<String> errors = new ArrayList<>(profile.validate());
        boolean duplicateName = profiles.stream()
                .anyMatch(other -> !other.getId().equals(profile.getId())
                        && other.getName().equalsIgnoreCase(profile.getName()));
        if (duplicateName) {
            errors.add("A server named \"" + profile.getName() + "\" already exists.");
        }
        if (!errors.isEmpty()) {
            throw new ProfileValidationException(errors);
        }
    }

    /** Applies the change to a copy, saves it, and only then swaps it in. */
    private void commit(Consumer<List<ServerProfile>> change) throws ProfileStoreException {
        List<ServerProfile> next = new ArrayList<>(profiles);
        change.accept(next);
        store.save(next);
        profiles.clear();
        profiles.addAll(next);
    }

    private int indexOf(UUID id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < profiles.size(); i++) {
            if (id.equals(profiles.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }
}
