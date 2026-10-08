package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.cloudflare.PublishPlan;
import mt.su.nrm.cloudflare.Zone;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The wording of the warning under the wizard's hostname. */
class CloudflareWizardWarningTest {

    private static final Zone ZONE = new Zone("z1", "example.com", "acc");

    @Test
    void anExistingNameIsWarnedAboutWithEachThingFound() {
        PublishPlan.Existing e = new PublishPlan.Existing(
                List.of("DNS example.com already has A www.example.com -> 1.2.3.4",
                        "Tunnel edge already publishes www.example.com -> http://localhost:80"), true, true);
        String text = CloudflareVhostWizard.existingText(e, "www.example.com", ZONE, List.of());
        assertTrue(text.startsWith("Warning: www.example.com already exists in Cloudflare."), text);
        assertTrue(text.contains("A www.example.com -> 1.2.3.4"), text);
        assertTrue(text.contains("Tunnel edge already publishes"), text);
    }

    @Test
    void aFreeNameSaysSoOnlyWhenEverythingWasChecked() {
        PublishPlan.Existing checked = new PublishPlan.Existing(List.of(), true, true);
        assertEquals("Nothing in Cloudflare uses new.example.com yet.",
                CloudflareVhostWizard.existingText(checked, "new.example.com", ZONE, List.of()));

        PublishPlan.Existing partial = new PublishPlan.Existing(List.of(), false, true);
        String text = CloudflareVhostWizard.existingText(partial, "new.example.com", ZONE, List.of());
        assertTrue(text.contains("could not be checked"), text);
        assertTrue(text.contains("DNS records of example.com"), text);
    }

    @Test
    void aFailureToReadIsShownEvenWhenNothingWasFound() {
        PublishPlan.Existing e = new PublishPlan.Existing(List.of(), true, true);
        String text = CloudflareVhostWizard.existingText(e, "new.example.com", ZONE,
                List.of("the routes of tunnel edge could not be read: boom"));
        assertTrue(text.contains("could not be checked"), text);
        assertTrue(text.contains("boom"), text);
    }

    @Test
    void noHostnameNoWarning() {
        assertEquals("", CloudflareVhostWizard.existingText(new PublishPlan.Existing(List.of(), true, true), "", ZONE,
                List.of()));
    }
}
