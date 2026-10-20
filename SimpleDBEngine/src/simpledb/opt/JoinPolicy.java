package simpledb.opt;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable eligibility configuration for joins, independent of method priority.
 * This configuration is currently passed through planners; candidate enforcement
 * is a separate implementation step.
 */
public final class JoinPolicy {
    private static final JoinPolicy DEFAULT =
            new JoinPolicy(EnumSet.allOf(JoinMethod.class));
    private final Set<JoinMethod> allowedMethods;

    /** Creates a policy with a defensive copy of a nonempty set of methods. */
    public JoinPolicy(Collection<JoinMethod> methods) {
        Objects.requireNonNull(methods, "methods");
        EnumSet<JoinMethod> copy = EnumSet.noneOf(JoinMethod.class);
        copy.addAll(methods);
        if (copy.isEmpty())
            throw new IllegalArgumentException("At least one join method is required");
        allowedMethods = Collections.unmodifiableSet(copy);
    }

    /** Allows all methods; existing planner priority remains unchanged. */
    public static JoinPolicy defaultPolicy() {
        return DEFAULT;
    }

    /** Creates a strict single-method configuration. */
    public static JoinPolicy only(JoinMethod method) {
        return new JoinPolicy(EnumSet.of(Objects.requireNonNull(method, "method")));
    }

    public boolean allows(JoinMethod method) {
        return allowedMethods.contains(Objects.requireNonNull(method, "method"));
    }

    /** Returns an unmodifiable set; its iteration order is not planner priority. */
    public Set<JoinMethod> allowedMethods() {
        return allowedMethods;
    }
}
