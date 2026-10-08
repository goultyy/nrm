package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.ssl.AltNames;
import mt.su.nrm.ssl.CertificateInfo;
import mt.su.nrm.ssl.SubjectInfo;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CertificateToolsTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);

    private final SshSessionTest.FakeTransport transport = new SshSessionTest.FakeTransport();

    private SshSession session() {
        return new SshSession(transport, new CommandLog(), PrivilegeMode.SUDO_PASSWORD, "pw", "u@h");
    }

    private static String nonceIn(String command) {
        Matcher m = Pattern.compile("@@NRM-([0-9a-f]+)").matcher(command);
        assertTrue(m.find(), command);
        return m.group(1);
    }

    // ------------------------------------------------------------------------------- parsing

    private static final String OPENSSL3 = "subject=CN = app.example.com\nissuer=C = US, O = Let's Encrypt, CN = R11\n"
            + "notBefore=Aug 20 10:00:00 2026 GMT\nnotAfter=Nov 18 10:00:00 2026 GMT\n"
            + "sha256 Fingerprint=AA:BB:CC\n            DNS:app.example.com, DNS:www.example.com, IP Address:10.0.0.5\n";

    @Test
    void parsesOpenssl3Output() {
        CertificateInfo c = CertificateInfo.parse("/etc/letsencrypt/live/app/fullchain.pem", OPENSSL3);
        assertNull(c.error());
        assertEquals("app.example.com", c.commonName());
        assertEquals(List.of("app.example.com", "www.example.com", "10.0.0.5"), c.names());
        assertEquals(Instant.parse("2026-11-18T10:00:00Z"), c.notAfter());
        assertEquals(Instant.parse("2026-08-20T10:00:00Z"), c.notBefore());
        assertEquals("AA:BB:CC", c.fingerprint());
        assertFalse(c.selfSigned());
        assertEquals(53, c.daysLeft(NOW));
        assertEquals(CertificateInfo.Status.OK, c.status(NOW));
    }

    @Test
    void parsesTheOlderSubjectFormatAndSingleDigitDays() {
        CertificateInfo c = CertificateInfo.parse("/x.crt", "subject= /C=AU/O=Acme/CN=old.example.com\n"
                + "issuer= /C=AU/O=Acme/CN=old.example.com\nnotBefore=Jan  5 00:00:00 2026 GMT\n"
                + "notAfter=Oct  4 12:30:00 2026 GMT\nSHA256 Fingerprint=11:22\n");
        assertEquals("old.example.com", c.commonName());
        assertTrue(c.selfSigned());
        assertEquals(Instant.parse("2026-10-04T12:30:00Z"), c.notAfter());
        assertEquals(List.of("old.example.com"), c.displayNames());
        assertEquals(CertificateInfo.Status.EXPIRING, c.status(NOW));
    }

    @Test
    void expiredAndUnreadableCertificatesAreFlagged() {
        CertificateInfo expired = CertificateInfo.parse("/x", "subject=CN = gone.example.com\nissuer=CN = gone.example.com\n"
                + "notBefore=Jan  1 00:00:00 2025 GMT\nnotAfter=Sep 20 00:00:00 2026 GMT\n");
        assertEquals(CertificateInfo.Status.EXPIRED, expired.status(NOW));
        assertEquals(-6, expired.daysLeft(NOW));

        CertificateInfo bad = CertificateInfo.parse("/etc/nginx/ssl/site.key",
                "Could not read certificate from /etc/nginx/ssl/site.key\nUnable to load certificate\n");
        assertNotNull(bad.error());
        assertEquals(CertificateInfo.Status.UNKNOWN, bad.status(NOW));
    }

    // ------------------------------------------------------------------------------- listing

    @Test
    void listScriptSearchesTheKnownFoldersAndQuotesConfiguredPaths() {
        ServerPaths paths = ServerPaths.defaults();
        String script = CertificateService.listScript("abc", CertificateService.explicitPaths(
                List.of("/etc/ssl/certs/site.pem", "/bad path/x.pem", "/tmp/x'; rm -rf /; '")),
                CertificateService.globs(paths));

        assertTrue(script.contains("for f in '/etc/ssl/certs/site.pem' "), script);
        assertTrue(script.contains("/etc/letsencrypt/live/*/fullchain.pem"));
        assertTrue(script.contains("/etc/nginx/ssl/*.crt"));
        assertTrue(script.contains("/etc/nrm/ca/ca.crt"));
        assertFalse(script.contains("rm -rf"));
        assertFalse(script.contains("bad path"));
        // Only certificates are read: no command in the script opens a key.
        assertFalse(script.contains(".key"));
        assertFalse(script.contains("privkey"));
    }

    @Test
    void listParsesEveryBlockAndDropsDuplicatesByRealPath() throws Exception {
        transport.responder = command -> {
            String n = nonceIn(command);
            String out = "@@NRM-" + n + " CERT\t/etc/letsencrypt/live/a/fullchain.pem\t/etc/letsencrypt/archive/a/fullchain1.pem\n"
                    + OPENSSL3 + "@@NRM-" + n + " ENDCERT\n"
                    + "@@NRM-" + n + " CERT\t/etc/nginx/ssl/a.pem\t/etc/letsencrypt/archive/a/fullchain1.pem\n"
                    + OPENSSL3 + "@@NRM-" + n + " ENDCERT\n"
                    + "@@NRM-" + n + " CERT\t/etc/nginx/ssl/junk.pem\t/etc/nginx/ssl/junk.pem\n"
                    + "Could not read certificate\n@@NRM-" + n + " ENDCERT\n";
            return SshSessionTest.FakeTransport.raw(0, out, "");
        };
        List<CertificateInfo> list = CertificateService.list(session(), ServerPaths.defaults(), List.of());
        assertEquals(2, list.size());
        assertEquals("/etc/letsencrypt/live/a/fullchain.pem", list.get(0).path());
        assertNotNull(list.get(1).error());
        assertTrue(transport.commands.get(0).startsWith("sudo -S"));
    }

    // ------------------------------------------------------------------------------- names

    @Test
    void namesAreClassifiedAndValidated() {
        AltNames.Parsed ok = AltNames.parse("Example.com, *.example.com 10.0.0.5;[::1]x  ::1", true);
        assertEquals(List.of("DNS:example.com", "DNS:*.example.com", "IP:10.0.0.5", "IP:::1"),
                ok.names().stream().map(AltNames.Name::entry).toList());
        assertEquals(1, ok.problems().size(), ok.problems().toString());

        assertEquals("DNS:a.example.com,IP:10.0.0.5", AltNames.subjectAltName(AltNames.parse("a.example.com 10.0.0.5", false).names()));
        assertFalse(AltNames.parse("*.example.com", false).problems().isEmpty());
        assertFalse(AltNames.parse("999.1.1.1", true).problems().isEmpty());
        assertFalse(AltNames.parse("bad_name.example.com", true).problems().isEmpty());
        assertFalse(AltNames.parse("x'; rm -rf /", true).problems().isEmpty());
        assertFalse(AltNames.parse("   ", true).problems().isEmpty());
        assertTrue(AltNames.isEmail("me@example.com"));
        assertFalse(AltNames.isEmail("me@localhost"));
    }

    // ------------------------------------------------------------------------------- certbot

    private static CertbotService.IssueRequest request(CertbotService.Method method) {
        return new CertbotService.IssueRequest(List.of("app.example.com", "www.example.com"), "me@example.com", method,
                "/var/www/html", false, "");
    }

    @Test
    void webrootIssueBuildsTheCertbotCommand() {
        String script = CertbotService.issueScript(ServerPaths.defaults(), request(CertbotService.Method.WEBROOT), "n1");
        assertTrue(script.contains("\"$C\" certonly --non-interactive --agree-tos -m 'me@example.com' --cert-name "
                + "'app.example.com' --webroot -w '/var/www/html' -d 'app.example.com' -d 'www.example.com' 2>&1"), script);
        assertTrue(script.contains("C='/usr/bin/certbot'"));
        assertTrue(script.contains("command -v certbot"));
        assertFalse(script.contains("--staging"));
        assertFalse(script.contains("--nginx"));
    }

    @Test
    void nginxPluginStagingAndNoEmailVariants() {
        var r = new CertbotService.IssueRequest(List.of("a.example.com"), "", CertbotService.Method.NGINX, "", true, "mysite");
        String script = CertbotService.issueScript(ServerPaths.defaults(), r, "n1");
        assertTrue(script.contains("--register-unsafely-without-email"));
        assertTrue(script.contains("--staging"));
        assertTrue(script.contains("--nginx -d 'a.example.com'"));
        assertTrue(script.contains("--cert-name 'mysite'"));
        assertFalse(script.contains("--webroot"));
    }

    @Test
    void certbotRequestsAreValidated() {
        var bad = new CertbotService.IssueRequest(List.of("*.example.com", "not valid", "ok.example.com"), "nope",
                CertbotService.Method.WEBROOT, "relative", false, "bad name!");
        List<String> problems = CertbotService.problems(bad);
        assertTrue(problems.stream().anyMatch(p -> p.contains("DNS challenge")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("not a valid domain")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("e-mail")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("webroot")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("certificate name")));
        assertTrue(CertbotService.problems(request(CertbotService.Method.WEBROOT)).isEmpty());
        assertThrows(IOException.class, () -> CertbotService.issue(session(), ServerPaths.defaults(), bad));
        assertTrue(transport.commands.isEmpty(), "nothing is sent for an invalid request");
    }

    @Test
    void issueReportsSuccessWithThePathsOfTheNewCertificate() throws Exception {
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0,
                "Successfully received certificate.\n@@NRM-" + nonceIn(c) + " DONE\n", "");
        CertbotService.Result r = CertbotService.issue(session(), ServerPaths.defaults(), request(CertbotService.Method.WEBROOT));
        assertTrue(r.ok());
        assertEquals("/etc/letsencrypt/live/app.example.com/fullchain.pem", r.certPath());
        assertEquals("/etc/letsencrypt/live/app.example.com/privkey.pem", r.keyPath());
        assertEquals("Successfully received certificate.", r.output());

        transport.responder = c -> SshSessionTest.FakeTransport.raw(1, "", "Challenge failed for domain app.example.com\n");
        CertbotService.Result failed = CertbotService.issue(session(), ServerPaths.defaults(), request(CertbotService.Method.WEBROOT));
        assertFalse(failed.ok());
        assertNull(failed.certPath());
        assertTrue(failed.output().contains("Challenge failed"));
    }

    @Test
    void renewBuildsTheCommandAndReloadsNginxOnlyOnSuccess() {
        String all = CertbotService.renewScript(ServerPaths.defaults(), "", false, "n1");
        assertTrue(all.contains("\"$C\" renew --non-interactive 2>&1"));
        assertTrue(all.indexOf("[ $rc -eq 0 ] || exit $rc") < all.indexOf("-s reload"));
        String one = CertbotService.renewScript(ServerPaths.defaults(), "app.example.com", true, "n1");
        assertTrue(one.contains("renew --non-interactive --cert-name 'app.example.com' --force-renewal"));
    }

    // ------------------------------------------------------------------------------- CA

    private static final CaService.CaRef ACME = new CaService.CaRef("/etc/nrm/ca/acme_ca");

    private static SubjectInfo subject(String cn) {
        return SubjectInfo.ofCommonName(cn);
    }

    private static CaService.CertRequest cert(String cn, String names, int days, CaService.KeyType key, CaService.Usage usage) {
        return new CaService.CertRequest(subject(cn), AltNames.parse(names, true, true).names(), days, key, usage);
    }

    @Test
    void createCaScriptKeepsTheKeyPrivateAndWritesTheFullSubject() throws Exception {
        SubjectInfo subject = new SubjectInfo("Acme Internal CA", "Acme Pty Ltd", "IT", "AU", "New South Wales", "Sydney",
                "pki@acme.example");
        String script = CaService.createCaScript(ServerPaths.defaults(),
                new CaService.CaRequest(subject, 3650, CaService.KeyType.RSA_4096), "n1");
        assertTrue(script.startsWith("umask 077\n"));
        assertTrue(script.contains("if [ -e \"$D/ca.key\" ] || [ -e \"$D/ca.crt\" ]; then echo '@@NRM-n1 EXISTS'; exit 60; fi"));
        assertTrue(script.contains("openssl genrsa -out \"$D/ca.key\" 4096"));
        assertTrue(script.contains("chmod 600 \"$D/ca.key\""));
        // Every identity detail reaches the certificate, in order from general to specific.
        assertTrue(script.contains("'[dn]' 'C=AU' 'ST=New South Wales' 'L=Sydney' 'O=Acme Pty Ltd' 'OU=IT' "
                + "'CN=Acme Internal CA' 'emailAddress=pki@acme.example'"), script);
        assertTrue(script.contains("'basicConstraints=critical,CA:TRUE'"));
        assertTrue(script.contains("'keyUsage=critical,keyCertSign,cRLSign'"));
        assertTrue(script.contains("-days 3650"));
        assertTrue(script.contains("trap 'rm -rf \"$T\"' EXIT"));
        assertFalse(script.contains("cat \"$D/ca.key\""));
        assertFalse(script.contains("base64"));
    }

    @Test
    void caKeyTypesAreHonoured() throws Exception {
        String ec = CaService.createCaScript(ServerPaths.defaults(),
                new CaService.CaRequest(subject("EC CA"), 365, CaService.KeyType.EC_P384), "n1");
        assertTrue(ec.contains("openssl ecparam -name secp384r1 -genkey -noout -out \"$D/ca.key\""), ec);
        assertFalse(ec.contains("genrsa"));
    }

    @Test
    void issueScriptCarriesSubjectSansUsageAndVerifies() throws Exception {
        SubjectInfo subject = new SubjectInfo("app.internal", "Acme", "Web", "AU", "NSW", "Sydney", "ops@acme.example");
        var request = new CaService.CertRequest(subject,
                AltNames.parse("www.app.internal *.app.internal 10.0.0.5 email:ops@acme.example uri:https://app.internal/api",
                        true, true).names(), 825, CaService.KeyType.RSA_2048, CaService.Usage.SERVER_AND_CLIENT);
        String script = CaService.issueScript(ServerPaths.defaults(), ACME, request, "n1");

        assertTrue(script.contains("N='app.internal'"), script);
        assertTrue(script.contains("O='/etc/nginx/ssl'"));
        assertTrue(script.contains("'C=AU' 'ST=NSW' 'L=Sydney' 'O=Acme' 'OU=Web' 'CN=app.internal' 'emailAddress=ops@acme.example'"), script);
        // The common name is added as a SAN because modern clients ignore the CN.
        assertTrue(script.contains("'subjectAltName=DNS:app.internal,DNS:www.app.internal,DNS:*.app.internal,IP:10.0.0.5,"
                + "email:ops@acme.example,URI:https://app.internal/api'"), script);
        assertTrue(script.contains("'basicConstraints=CA:FALSE'"));
        assertTrue(script.contains("'extendedKeyUsage=serverAuth,clientAuth'"));
        assertTrue(script.contains("'authorityKeyIdentifier=keyid'"));
        assertTrue(script.contains("openssl genrsa -out \"$T/key.pem\" 2048"));
        assertTrue(script.contains("-CA \"$D/ca.crt\" -CAkey \"$D/ca.key\" -CAcreateserial -CAserial \"$D/ca.srl\""));
        assertTrue(script.contains("-days 825"));
        assertTrue(script.indexOf("openssl verify") > script.indexOf("openssl x509 -req"));
        assertTrue(script.contains("chmod 600 \"$O/$N.key\""));
        assertTrue(script.contains("FILE-EXISTS") && script.contains("NO-CA"));
        assertFalse(script.contains("cat \"$T/key.pem\""), "the new key is copied on the server, never printed");
    }

    @Test
    void theCommonNameDefaultsToTheFirstHostNameAndClientCertificatesNeedNoSans() throws Exception {
        var noCn = cert("", "app.internal 10.0.0.5", 30, CaService.KeyType.EC_P256, CaService.Usage.SERVER);
        assertEquals("app.internal", noCn.commonName());
        assertTrue(CaService.issueScript(ServerPaths.defaults(), ACME, noCn, "n1").contains("'CN=app.internal'"));

        var client = new CaService.CertRequest(new SubjectInfo("Jane Smith", "Acme", "", "AU", "", "", "jane@acme.example"),
                List.of(), 365, CaService.KeyType.EC_P256, CaService.Usage.CLIENT);
        assertTrue(CaService.problems(client).isEmpty(), CaService.problems(client).toString());
        String script = CaService.issueScript(ServerPaths.defaults(), ACME, client, "n1");
        assertFalse(script.contains("subjectAltName"), "no SAN when there are no names");
        assertFalse(script.contains("req_extensions"));
        assertTrue(script.contains("'extendedKeyUsage=clientAuth'"));
        assertTrue(script.contains("N='Jane_Smith'"));
        assertEquals(List.of(), client.effectiveNames(), "a person's name is not added as a host name");
    }

    @Test
    void ecKeysAndWildcardFileNames() throws Exception {
        var request = cert("*.corp.internal", "", 90, CaService.KeyType.EC_P256, CaService.Usage.SERVER);
        assertEquals("wildcard.corp.internal", CaService.fileBase(request));
        assertEquals(List.of("DNS:*.corp.internal"), request.effectiveNames().stream().map(AltNames.Name::entry).toList());
        String script = CaService.issueScript(ServerPaths.defaults(), ACME, request, "n1");
        assertTrue(script.contains("openssl ecparam -name prime256v1 -genkey -noout -out \"$T/key.pem\""));
        assertTrue(script.contains("'keyUsage=digitalSignature'"));
    }

    @Test
    void requestsAreValidated() {
        assertFalse(CaService.problems(new CaService.CaRequest(subject("bad/name'"), 3650, CaService.KeyType.RSA_4096)).isEmpty());
        assertFalse(CaService.problems(new CaService.CaRequest(subject(""), 3650, CaService.KeyType.RSA_4096)).isEmpty());
        assertFalse(CaService.problems(new CaService.CaRequest(subject("Ok CA"), 5, CaService.KeyType.RSA_4096)).isEmpty());
        assertFalse(CaService.problems(new CaService.CaRequest(
                new SubjectInfo("Ok CA", "x;y", "", "", "", "", ""), 3650, CaService.KeyType.RSA_4096)).isEmpty());
        assertTrue(CaService.problems(new CaService.CaRequest(subject("Ok CA"), 3650, CaService.KeyType.RSA_4096)).isEmpty());

        // Subject details.
        assertFalse(new SubjectInfo("a", "", "", "au", "", "", "").problems(false).isEmpty(), "country must be capitals");
        assertFalse(new SubjectInfo("a", "", "", "AUS", "", "", "").problems(false).isEmpty());
        assertFalse(new SubjectInfo("a", "", "", "", "", "", "not-an-email").problems(false).isEmpty());
        assertFalse(new SubjectInfo("a", "$HOME", "", "", "", "", "").problems(false).isEmpty(), "no variable expansion in config files");
        assertTrue(new SubjectInfo("a", "Acme & Sons (Pty) Ltd.", "R+D", "US", "New York", "St. Paul", "me@x.example").problems(false).isEmpty());

        // Certificate rules.
        assertFalse(CaService.problems(cert("", "", 30, CaService.KeyType.RSA_2048, CaService.Usage.SERVER)).isEmpty());
        assertTrue(CaService.problems(cert("app.internal", "email:me@x.example", 30, CaService.KeyType.RSA_2048,
                CaService.Usage.SERVER)).isEmpty(), "the common name supplies the host name");
        assertTrue(CaService.problems(cert("Jane Smith", "email:jane@x.example", 30, CaService.KeyType.RSA_2048,
                CaService.Usage.SERVER)).stream().anyMatch(p -> p.contains("DNS name or IP")));
        assertFalse(CaService.problems(cert("a.internal", "", 0, CaService.KeyType.RSA_2048, CaService.Usage.SERVER)).isEmpty());
    }

    @Test
    void unsafeFoldersAreRefused() {
        ServerPaths unsafe = ServerPaths.defaults();
        unsafe.setCaStorageDir("/etc/ca dir/x'; rm -rf /; '");
        assertThrows(IOException.class, () -> CaService.createCaScript(unsafe,
                new CaService.CaRequest(subject("Ok CA"), 3650, CaService.KeyType.RSA_4096), "n1"));
    }

    @Test
    void subjectAltNameTypesAreParsedAndInjectionIsRejected() {
        var parsed = AltNames.parse("a.example.com email:me@example.com uri:https://x.example/p?q=1 10.1.1.1", true, true);
        assertTrue(parsed.problems().isEmpty(), parsed.problems().toString());
        assertEquals(List.of("DNS:a.example.com", "email:me@example.com", "URI:https://x.example/p?q=1", "IP:10.1.1.1"),
                parsed.names().stream().map(AltNames.Name::entry).toList());
        // E-mail and URI names are refused where they don't belong (Let's Encrypt, plain host lists).
        assertFalse(AltNames.parse("email:me@example.com", true).problems().isEmpty());
        assertFalse(AltNames.parse("uri:https://x.example/$(id)", true, true).problems().isEmpty());
        assertFalse(AltNames.parse("uri:https://x.example/#frag", true, true).problems().isEmpty());
        assertFalse(AltNames.parse("email:bad@", true, true).problems().isEmpty());
    }

    @Test
    void createIssueAndFetchRunThroughTheSession() throws Exception {
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, "Generating...\n@@NRM-" + nonceIn(c) + " CA-CREATED\n", "");
        var caRequest = new CaService.CaRequest(subject("Acme CA"), 3650, CaService.KeyType.RSA_4096);
        CaService.Result ca = CaService.createCa(session(), ServerPaths.defaults(), caRequest);
        assertTrue(ca.ok());
        assertEquals("/etc/nrm/ca/acme_ca/ca.crt", ca.certPath());

        transport.responder = c -> SshSessionTest.FakeTransport.raw(60, "@@NRM-" + nonceIn(c) + " EXISTS\n", "");
        CaService.Result again = CaService.createCa(session(), ServerPaths.defaults(), caRequest);
        assertFalse(again.ok());
        assertTrue(again.output().contains("already exists in /etc/nrm/ca/acme_ca"));

        var request = cert("app.internal", "", 825, CaService.KeyType.RSA_2048, CaService.Usage.SERVER);
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, "cert.pem: OK\n@@NRM-" + nonceIn(c) + " ISSUED\n", "");
        CaService.Result issued = CaService.issue(session(), ServerPaths.defaults(), ACME, request);
        assertTrue(issued.ok());
        assertEquals("/etc/nginx/ssl/app.internal.crt", issued.certPath());
        assertEquals("/etc/nginx/ssl/app.internal.key", issued.keyPath());

        transport.responder = c -> SshSessionTest.FakeTransport.raw(70, "@@NRM-" + nonceIn(c) + " NO-CA\n", "");
        assertTrue(CaService.issue(session(), ServerPaths.defaults(), ACME, request).output().contains("no certificate authority"));

        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n", "");
        assertTrue(CaService.fetchCaCertificate(session(), ServerPaths.defaults(), ACME).contains("BEGIN CERTIFICATE"));
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, "-----BEGIN PRIVATE KEY-----\nabc\n", "");
        assertThrows(IOException.class, () -> CaService.fetchCaCertificate(session(), ServerPaths.defaults(), ACME));

        transport.commands.clear();
        assertThrows(IOException.class, () -> CaService.issue(session(), ServerPaths.defaults(), ACME,
                cert("", "", 825, CaService.KeyType.RSA_2048, CaService.Usage.SERVER)));
        assertTrue(transport.commands.isEmpty(), "nothing is sent for an invalid request");
    }

    // ------------------------------------------------------------------------------- richer certificate information

    @Test
    void parsesSerialKeySignatureCaFlagAndAllSanTypes() {
        CertificateInfo c = CertificateInfo.parse("/etc/nginx/ssl/a.crt",
                "subject=C = AU, ST = NSW, L = Sydney, O = Acme, OU = Web, CN = app.internal, emailAddress = ops@acme.example\n"
                        + "issuer=CN = Acme CA\nserial=0A1B2C\nnotBefore=Jan  1 00:00:00 2026 GMT\nnotAfter=Jan  1 00:00:00 2027 GMT\n"
                        + "sha256 Fingerprint=AA:BB\n            DNS:app.internal, IP Address:10.0.0.5, email:ops@acme.example, URI:https://app.internal/\n"
                        + "Public Key Algorithm: rsaEncryption\n                Public-Key: (2048 bit)\n"
                        + "    Signature Algorithm: sha256WithRSAEncryption\n    Signature Algorithm: sha256WithRSAEncryption\n"
                        + "                CA:FALSE\n");
        assertEquals("app.internal", c.commonName());
        assertEquals("Acme", c.subjectField("O"));
        assertEquals("Web", c.subjectField("OU"));
        assertEquals("AU", c.subjectField("C"));
        assertEquals("NSW", c.subjectField("ST"));
        assertEquals("Sydney", c.subjectField("L"));
        assertEquals("ops@acme.example", c.subjectField("emailAddress"));
        assertEquals("Acme CA", c.issuerField("CN"));
        assertEquals("0A1B2C", c.serial());
        assertEquals("RSA 2048-bit", c.keyInfo());
        assertEquals("sha256WithRSAEncryption", c.signature());
        assertFalse(c.authority());
        assertEquals(List.of("app.internal", "10.0.0.5", "email:ops@acme.example", "URI:https://app.internal/"), c.names());
        String details = c.details();
        assertTrue(details.contains("Organisation") && details.contains("Acme"), details);
        assertTrue(details.contains("ops@acme.example") && details.contains("0A1B2C") && details.contains("RSA 2048-bit"), details);
    }

    @Test
    void recognisesCertificateAuthoritiesAndEcKeys() {
        CertificateInfo ca = CertificateInfo.parse("/etc/nrm/ca/ca.crt",
                "subject=O = Acme, CN = Acme CA\nissuer=O = Acme, CN = Acme CA\nnotBefore=Jan  1 00:00:00 2026 GMT\n"
                        + "notAfter=Jan  1 00:00:00 2036 GMT\nPublic Key Algorithm: id-ecPublicKey\n"
                        + "                Public-Key: (384 bit)\n                ASN1 OID: secp384r1\n                NIST CURVE: P-384\n"
                        + "                CA:TRUE\n");
        assertTrue(ca.authority());
        assertTrue(ca.selfSigned());
        assertEquals("EC secp384r1", ca.keyInfo());
        assertTrue(ca.details().startsWith("Certificate authority"));
    }

    @Test
    void listScriptAsksForTheExtraFields() {
        String script = CertificateService.listScript("abc", List.of(), List.of("/etc/nginx/ssl/*.crt"));
        assertTrue(script.contains("-serial"));
        assertTrue(script.contains("Public-Key:") && script.contains("Signature Algorithm:") && script.contains("CA:(TRUE|FALSE)"));
        assertFalse(script.contains("privkey"));
    }

    // ------------------------------------------------------------------------------- several authorities

    @Test
    void eachAuthorityGetsItsOwnFolderNamedAfterIt() throws Exception {
        assertEquals("acme_ca", CaService.defaultFolder("Acme CA"));
        assertEquals("dev-team.ca", CaService.defaultFolder("Dev-Team.CA"));
        assertEquals(40, CaService.defaultFolder("x".repeat(80)).length());

        String script = CaService.createCaScript(ServerPaths.defaults(),
                new CaService.CaRequest(subject("Prod CA"), 365, CaService.KeyType.RSA_4096, "prod"), "n1");
        assertTrue(script.contains("D='/etc/nrm/ca/prod'"), script);
        String other = CaService.createCaScript(ServerPaths.defaults(),
                new CaService.CaRequest(subject("Dev CA"), 365, CaService.KeyType.RSA_4096, "dev"), "n1");
        assertTrue(other.contains("D='/etc/nrm/ca/dev'"), other);
        assertFalse(CaService.problems(new CaService.CaRequest(subject("X"), 365, CaService.KeyType.RSA_4096, "../evil")).isEmpty());
        assertFalse(CaService.problems(new CaService.CaRequest(subject("X"), 365, CaService.KeyType.RSA_4096, "UPPER")).isEmpty());
    }

    @Test
    void issuingUsesTheChosenAuthoritysFolder() throws Exception {
        var request = cert("app.internal", "", 30, CaService.KeyType.RSA_2048, CaService.Usage.SERVER);
        String prod = CaService.issueScript(ServerPaths.defaults(), new CaService.CaRef("/etc/nrm/ca/prod"), request, "n1");
        String dev = CaService.issueScript(ServerPaths.defaults(), new CaService.CaRef("/etc/nrm/ca/dev"), request, "n1");
        assertTrue(prod.contains("D='/etc/nrm/ca/prod'") && !prod.contains("/ca/dev"));
        assertTrue(dev.contains("D='/etc/nrm/ca/dev'") && !dev.contains("/ca/prod"));
    }

    @Test
    void anAuthorityIsFoundOnlyInsideTheCaFolder() throws Exception {
        ServerPaths paths = ServerPaths.defaults();
        assertEquals(new CaService.CaRef("/etc/nrm/ca/prod"), CaService.authority(paths, "/etc/nrm/ca/prod/ca.crt"));
        assertEquals(new CaService.CaRef("/etc/nrm/ca"), CaService.authority(paths, "/etc/nrm/ca/ca.crt"), "a single CA from before");
        assertEquals("prod", CaService.authority(paths, "/etc/nrm/ca/prod/ca.crt").folder(paths));
        assertEquals("", CaService.authority(paths, "/etc/nrm/ca/ca.crt").folder(paths));
        for (String bad : List.of("/etc/passwd", "/etc/nrm/ca/a/b/ca.crt", "/etc/nrm/other/ca.crt", "/etc/nrm/ca/x'y/ca.crt",
                "/etc/nrm/ca/prod/ca.key", "/etc/nrm/ca/../ca.crt")) {
            assertThrows(IOException.class, () -> CaService.authority(paths, bad), bad);
        }
    }

    @Test
    void listingFindsEveryAuthorityFolderAndTheOldSingleCa() {
        List<String> globs = CertificateService.globs(ServerPaths.defaults());
        assertTrue(globs.contains("/etc/nrm/ca/*/ca.crt"));
        assertTrue(globs.contains("/etc/nrm/ca/ca.crt"));
    }

    @Test
    void theOutputFileNameCanBeChosenSoTwoAuthoritiesCanIssueTheSameName() throws Exception {
        var plain = cert("app.internal", "", 30, CaService.KeyType.RSA_2048, CaService.Usage.SERVER);
        var named = new CaService.CertRequest(plain.subject(), plain.names(), 30, CaService.KeyType.RSA_2048,
                CaService.Usage.SERVER, "app.internal-dev");
        assertEquals("app.internal", CaService.fileBase(plain));
        assertEquals("app.internal-dev", CaService.fileBase(named));
        assertTrue(CaService.issueScript(ServerPaths.defaults(), ACME, named, "n1").contains("N='app.internal-dev'"));
        var unsafe = new CaService.CertRequest(plain.subject(), plain.names(), 30, CaService.KeyType.RSA_2048,
                CaService.Usage.SERVER, "../../etc/x y");
        assertEquals("etc_x_y", CaService.fileBase(unsafe));
    }
}
