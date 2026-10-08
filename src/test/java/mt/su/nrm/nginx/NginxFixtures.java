package mt.su.nrm.nginx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The sample nginx configuration files in {@code src/test/resources/nginx}, read as text with {@code \n} line endings
 * whatever the checkout produced. Git on Windows can check text files out with {@code \r\n}, and the tests compare
 * against literal {@code \n}; reading through here means they pass either way. (.gitattributes also keeps these files
 * as {@code \n} on disk.)
 */
public final class NginxFixtures {

    private NginxFixtures() {
    }

    public static String read(String name) throws IOException {
        try (InputStream in = NginxFixtures.class.getResourceAsStream("/nginx/" + name)) {
            if (in == null) {
                throw new IOException("No test fixture /nginx/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
