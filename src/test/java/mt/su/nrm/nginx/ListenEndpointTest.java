package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ListenEndpointTest {

    private static ListenEndpoint of(String text) {
        return ListenEndpoint.parse(text).orElseThrow();
    }

    @Test
    void aBarePortHasNoHost() {
        assertEquals(new ListenEndpoint("", 443), of("443"));
    }

    @Test
    void hostAndPortAreSplit() {
        assertEquals(new ListenEndpoint("127.0.0.1", 8080), of("127.0.0.1:8080"));
        assertEquals(new ListenEndpoint("*", 80), of("*:80"));
        assertEquals(new ListenEndpoint("[::]", 443), of("[::]:443"));
    }

    @Test
    void aBareAddressMeansPort80LikeNginx() {
        assertEquals(new ListenEndpoint("localhost", 80), of("localhost"));
        assertEquals(new ListenEndpoint("[::1]", 80), of("[::1]"));
    }

    @Test
    void socketsAndNonsenseHaveNoPort() {
        assertEquals(Optional.empty(), ListenEndpoint.parse("unix:/run/nginx.sock"));
        assertEquals(Optional.empty(), ListenEndpoint.parse(""));
        assertEquals(Optional.empty(), ListenEndpoint.parse("0"));
        assertEquals(Optional.empty(), ListenEndpoint.parse("70000"));
        assertEquals(Optional.empty(), ListenEndpoint.parse("host:abc"));
        assertTrue(ListenEndpoint.parse(null).isEmpty());
    }
}
