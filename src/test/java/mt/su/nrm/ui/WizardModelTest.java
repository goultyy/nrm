package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WizardModelTest {

    private static WizardModel model(AtomicReference<List<String>> first, AtomicReference<List<String>> second) {
        return new WizardModel(List.of(
                WizardModel.step("Name", first::get),
                WizardModel.step("Servers", second::get),
                WizardModel.step("Review", List::of)));
    }

    @Test
    void nextIsOnlyPossibleWhileTheCurrentStepHasNoProblems() {
        var first = new AtomicReference<>(List.of("A name is required."));
        var second = new AtomicReference<List<String>>(List.of());
        WizardModel m = model(first, second);

        assertTrue(m.isFirst());
        assertFalse(m.canGoNext());
        assertFalse(m.next());
        assertEquals(0, m.index());

        first.set(List.of());
        assertTrue(m.canGoNext());
        assertTrue(m.next());
        assertEquals(1, m.index());
        assertEquals("Step 2 of 3: Servers", m.heading());
    }

    @Test
    void backAlwaysWorksExceptOnTheFirstStep() {
        var none = new AtomicReference<List<String>>(List.of());
        WizardModel m = model(none, none);
        assertFalse(m.back());
        m.next();
        m.next();
        assertTrue(m.isLast());
        assertTrue(m.back());
        assertEquals(1, m.index());
    }

    @Test
    void finishNeedsTheLastStepAndEveryEarlierStepInOrder() {
        var first = new AtomicReference<List<String>>(List.of());
        var second = new AtomicReference<List<String>>(List.of());
        WizardModel m = model(first, second);
        assertFalse(m.canFinish(), "not on the last step yet");
        m.next();
        m.next();
        assertTrue(m.canFinish());
        assertFalse(m.canGoNext(), "there is nothing after the last step");

        // The user went back, broke something, and came forward again: Finish is blocked.
        second.set(List.of("Add at least one server."));
        assertFalse(m.canFinish());
        assertEquals(List.of("Add at least one server."), m.allProblems());
    }

    @Test
    void aWizardNeedsSteps() {
        assertThrows(IllegalArgumentException.class, () -> new WizardModel(List.of()));
    }

    @Test
    void stepsThatDoNotApplyAreSkippedBothWaysAndNotCounted() {
        var passwordWanted = new AtomicReference<>(false);
        WizardModel m = new WizardModel(List.of(
                WizardModel.step("Who", List::of),
                WizardModel.step("Login", () -> List.of("Add a user."), passwordWanted::get),
                WizardModel.step("Review", List::of)));
        assertEquals(2, m.count());
        assertTrue(m.next());
        assertEquals(2, m.index());
        assertEquals("Step 2 of 2: Review", m.heading());
        assertTrue(m.isLast());
        assertTrue(m.canFinish(), "a skipped step's problems must not block finishing");
        assertTrue(m.back());
        assertEquals(0, m.index());

        passwordWanted.set(true);
        assertEquals(3, m.count());
        assertTrue(m.next());
        assertEquals(1, m.index());
        assertFalse(m.canGoNext());
    }
}
