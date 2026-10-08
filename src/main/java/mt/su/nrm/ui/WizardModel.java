package mt.su.nrm.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The navigation rules of a wizard, kept free of JavaFX so they can be tested: which step is
 * showing, whether Next is allowed (only when the current step has no problems), and whether the
 * current step is the last (where Finish replaces Next). A step can say it doesn't apply to the
 * choices made so far (a password page for a network-only setup); such steps are skipped in both
 * directions and left out of the step count.
 */
final class WizardModel {

    /** One step: its title and what is wrong with it right now. */
    interface Step {
        String title();

        /** Problems with the current input on this step; the user can't move on until this is empty. */
        List<String> problems();

        /** False if the choices made on earlier steps make this step unnecessary. */
        default boolean applies() {
            return true;
        }
    }

    private final List<? extends Step> steps;
    private int index;

    WizardModel(List<? extends Step> steps) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("A wizard needs at least one step.");
        }
        this.steps = steps;
    }

    int index() {
        return index;
    }

    /** The number of steps that apply right now. */
    int count() {
        int n = 0;
        for (Step s : steps) {
            if (s.applies()) {
                n++;
            }
        }
        return n;
    }

    Step current() {
        return steps.get(index);
    }

    /** The index of the nearest applicable step in a direction, or -1 if there is none. */
    private int neighbour(int direction) {
        for (int i = index + direction; i >= 0 && i < steps.size(); i += direction) {
            if (steps.get(i).applies()) {
                return i;
            }
        }
        return -1;
    }

    boolean isFirst() {
        return neighbour(-1) < 0;
    }

    boolean isLast() {
        return neighbour(1) < 0;
    }

    boolean canGoNext() {
        return !isLast() && current().problems().isEmpty();
    }

    /** Finish is offered on the last step, and only when every step that applies is in order. */
    boolean canFinish() {
        if (!isLast()) {
            return false;
        }
        for (Step s : steps) {
            if (s.applies() && !s.problems().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    boolean next() {
        if (!canGoNext()) {
            return false;
        }
        index = neighbour(1);
        return true;
    }

    boolean back() {
        int previous = neighbour(-1);
        if (previous < 0) {
            return false;
        }
        index = previous;
        return true;
    }

    /** "Step 2 of 4: Servers". */
    String heading() {
        int position = 0;
        for (int i = 0; i <= index; i++) {
            if (steps.get(i).applies()) {
                position++;
            }
        }
        return "Step " + position + " of " + count() + ": " + current().title();
    }

    /** All problems on every step that applies, for showing why Finish is unavailable. */
    List<String> allProblems() {
        List<String> all = new ArrayList<>();
        for (Step s : steps) {
            if (s.applies()) {
                all.addAll(s.problems());
            }
        }
        return all;
    }

    /** Convenience for building a step from lambdas. */
    static Step step(String title, Supplier<List<String>> problems) {
        return step(title, problems, () -> true);
    }

    static Step step(String title, Supplier<List<String>> problems, Supplier<Boolean> applies) {
        return new Step() {
            @Override
            public String title() {
                return title;
            }

            @Override
            public List<String> problems() {
                return problems.get();
            }

            @Override
            public boolean applies() {
                return applies.get();
            }
        };
    }
}
