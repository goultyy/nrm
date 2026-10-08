package mt.su.nrm.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A profile can't be saved; {@link #errors()} lists every problem for display. */
public final class ProfileValidationException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final ArrayList<String> errors;

    public ProfileValidationException(List<String> errors) {
        super(String.join(" ", errors));
        this.errors = new ArrayList<>(errors);
    }

    public List<String> errors() {
        return Collections.unmodifiableList(errors);
    }
}
