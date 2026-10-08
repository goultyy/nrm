package mt.su.nrm.ssl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The identity details in a certificate's subject: who it is for and where they are. Everything is
 * optional except the common name, and every value is restricted to plain characters so it can be
 * written safely into an openssl configuration file.
 *
 * @param commonName         the certificate's main name (CN); for a server certificate this may be left
 *                           blank and the first DNS name or IP address is used
 * @param organisation       O
 * @param organisationalUnit OU
 * @param country            C, two capital letters, e.g. AU
 * @param state              ST
 * @param locality           L (city)
 * @param email              emailAddress
 */
public record SubjectInfo(String commonName, String organisation, String organisationalUnit, String country,
                          String state, String locality, String email) {

    /** Letters, digits, spaces and a few marks; nothing openssl's config syntax treats specially. */
    private static final Pattern TEXT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 .,_@&()+-]{0,63}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    public SubjectInfo {
        commonName = clean(commonName);
        organisation = clean(organisation);
        organisationalUnit = clean(organisationalUnit);
        country = clean(country);
        state = clean(state);
        locality = clean(locality);
        email = clean(email);
    }

    public static SubjectInfo ofCommonName(String commonName) {
        return new SubjectInfo(commonName, "", "", "", "", "", "");
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }

    /** A copy with a different common name. */
    public SubjectInfo withCommonName(String cn) {
        return new SubjectInfo(cn, organisation, organisationalUnit, country, state, locality, email);
    }

    /** What is wrong with the details, worded for the user; empty if fine. */
    public List<String> problems(boolean commonNameRequired) {
        List<String> problems = new ArrayList<>();
        if (commonName.isEmpty()) {
            if (commonNameRequired) {
                problems.add("A common name is required.");
            }
        } else {
            checkText(problems, "Common name", commonName);
        }
        checkText(problems, "Organisation", organisation);
        checkText(problems, "Organisational unit", organisationalUnit);
        checkText(problems, "State or province", state);
        checkText(problems, "City or locality", locality);
        if (!country.isEmpty() && !COUNTRY.matcher(country).matches()) {
            problems.add("The country must be a two-letter code in capitals, such as AU or US.");
        }
        if (!email.isEmpty() && !AltNames.isEmail(email)) {
            problems.add("The e-mail address doesn't look right.");
        }
        return problems;
    }

    private static void checkText(List<String> problems, String label, String value) {
        if (!value.isEmpty() && !TEXT.matcher(value).matches()) {
            problems.add(label + " may use letters, digits, spaces and . , _ @ & ( ) + - (up to 64 characters).");
        }
    }

    /** The lines for an openssl {@code [dn]} section, most general first. */
    public List<String> configLines() {
        List<String> lines = new ArrayList<>();
        add(lines, "C", country);
        add(lines, "ST", state);
        add(lines, "L", locality);
        add(lines, "O", organisation);
        add(lines, "OU", organisationalUnit);
        add(lines, "CN", commonName);
        add(lines, "emailAddress", email);
        return lines;
    }

    private static void add(List<String> lines, String key, String value) {
        if (!value.isEmpty()) {
            lines.add(key + "=" + value);
        }
    }
}
