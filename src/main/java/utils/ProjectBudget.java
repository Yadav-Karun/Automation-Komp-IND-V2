package utils;

import java.util.ArrayList;
import java.util.List;

import org.testng.SkipException;

/**
 * The number of projects a test class may CREATE in one run (rule of the user: "too many projects"): the happy flow creates 1 project per type, the edge cases
 * at most 5 per type. Every click on "Create Project" that can create a project asks {@link #reserve} first - when the limit is reached the click does not
 * happen and the step is reported as SKIPPED with the reason; every project the application accepted is booked with {@link #created}.
 * A project that was accepted although it should have been refused (a duplicate, ...) counts like any other: it exists on the sandbox and cannot be deleted
 * by automation.
 */
public final class ProjectBudget {

    /** Happy flow: one project per type (AOR, EOR) and run. */
    public static final int HAPPY_FLOW_LIMIT = 1;
    /** Edge cases: five projects per type (AOR, EOR) and run; every scenario has to fit into them. */
    public static final int EDGE_CASES_LIMIT = 5;

    private final String owner;
    private final int limit;
    private final List<String> projects = new ArrayList<>();

    public ProjectBudget(String owner, int limit) {
        this.owner = owner;
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }

    public int used() {
        return projects.size();
    }

    public int remaining() {
        return limit - projects.size();
    }

    /** Asked BEFORE a click on Create Project: throws a SkipException (the step is skipped) when no project may be created any more. */
    public void reserve(String what) {
        if (remaining() <= 0) {
            throw new SkipException("Project limit reached: " + owner + " may create at most " + limit + " project(s) per run and " + used()
                    + " exist already (" + String.join(" | ", projects) + "). '" + what + "' was not submitted.");
        }
    }

    /** Books a project the application accepted. */
    public void created(String name) {
        projects.add(name == null ? "(no name)" : name.length() > 40 ? name.substring(0, 37) + "..." : name);
        Log.info("Project budget " + owner + ": " + used() + " of " + limit + " used");
    }

    @Override
    public String toString() {
        return used() + " of " + limit + " project(s) created";
    }
}
