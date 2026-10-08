package mt.su.nrm.ssh;

import mt.su.nrm.model.ServerProfile;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

/** Connects, checks the requirements and disconnects. Blocking: run it through {@link SshExecutor}. */
public final class ConnectionTester {

    /**
     * @param newlyPinnedFingerprint set when this was the first connection and the host key was pinned
     * @param newlyPinnedGatewayFingerprint set when the gateway's host key was pinned on this connection
     * @param requirements           what was found on the server
     */
    public record Report(String newlyPinnedFingerprint, String newlyPinnedGatewayFingerprint,
                         RequirementChecker.Requirements requirements) {
        public Report(String newlyPinnedFingerprint, RequirementChecker.Requirements requirements) {
            this(newlyPinnedFingerprint, null, requirements);
        }
    }

    private ConnectionTester() {
    }

    public static Report test(ServerProfile profile, Credentials credentials, CommandLog log, Clock clock)
            throws IOException {
        AtomicReference<String> pinned = new AtomicReference<>();
        AtomicReference<String> gatewayPinned = new AtomicReference<>();
        try (SshSession session = SshSession.connect(profile, credentials, log, pinned::set, gatewayPinned::set)) {
            RequirementChecker.Requirements requirements =
                    RequirementChecker.check(session, profile.getPaths(), clock);
            return new Report(pinned.get(), gatewayPinned.get(), requirements);
        }
    }
}
