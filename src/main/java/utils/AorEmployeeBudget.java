package utils;

/**
 * The employee limit of the AOR edge and negative cases (user's rule, 2026-11-11: first 5, raised to 25 for the comprehensive bulk-upload cases). Employees cannot be deleted by automation, so
 * every upload that could create an employee asks {@link #reserve} first (not enough left = the upload does not happen, the step is skipped with the reason) and every
 * employee the application really created is booked with {@link #created} - also one that the application wrongly accepted.
 */
public final class AorEmployeeBudget {

    private final int limit;
    private int created;

    public AorEmployeeBudget() {
        this.limit = ConfigManager.getInt("aor.edge.employees.max", 100000); // no limit for the edge cases (user, 2026-11-11): the Aadhaar / PAN pairs of the QA owner are the only supply; a value can still be set
        this.created = AorEdgeRegistry.count(); // employees of EARLIER edge runs count too: the limit is for all runs together
    }

    public int limit() {
        return limit;
    }

    public int created() {
        return created;
    }

    public int remaining() {
        return Math.max(0, limit - created);
    }

    /** True when {@code n} more employees may still be created. */
    public boolean reserve(int n) {
        return remaining() >= n;
    }

    public void created(int n) {
        created += Math.max(0, n);
    }

    @Override
    public String toString() {
        return limit >= 100000 ? "employees created " + created + " (no limit)" : "employees created " + created + " of " + limit;
    }
}
