package reporting;

import java.util.Set;

/**
 * Where each test case lives and the exact command to re-run it, for the "TC Catalog" sheet and the Jira bugs.
 * There are only two suites / two test classes:
 *   testng-client-happy-flow.xml  -> tests.ClientOnboardingE2EHappyFlow  (1 client, register ... Active, profile check)
 *   testng-client-edge-cases.xml  -> tests.ClientOnboardingEdgeCases     (1 client, every negative / edge case)
 */
public final class TcCatalog {

    public static final String HAPPY_XML = "testng-client-happy-flow.xml";
    public static final String EDGE_XML = "testng-client-edge-cases.xml";
    public static final String HAPPY_CLASS = "tests.ClientOnboardingE2EHappyFlow";
    public static final String EDGE_CLASS = "tests.ClientOnboardingEdgeCases";

    /**
     * Cases that create an EXTRA client when the application accepts their (invalid / special) input. The 2-client rule
     * keeps them out of a full run: they only run when named in -Dcases.
     */
    public static final Set<String> SIDE_EFFECT = Set.of("R-31", "R-50", "R-51", "R-52");

    private TcCatalog() {
    }

    private static boolean happy(ResultCollector.Case c) {
        String id = c.id == null ? "" : c.id;
        String g = c.group == null ? "" : c.group;
        return id.startsWith("TC-E2E") || id.startsWith("E2E-") || g.startsWith("E2E") || g.startsWith("C5 API health (observed");
    }

    public static String suite(ResultCollector.Case c) {
        return happy(c) ? HAPPY_XML : EDGE_XML;
    }

    public static String testClass(ResultCollector.Case c) {
        return happy(c) ? HAPPY_CLASS : EDGE_CLASS;
    }

    /** Does the case belong to the lifecycle of the ONE edge-case client (set-up steps register it on demand)? */
    public static boolean needsEdgeClient(ResultCollector.Case c) {
        String g = c.group == null ? "" : c.group;
        String id = c.id == null ? "" : c.id;
        return g.startsWith("A3") || g.startsWith("A4") || g.startsWith("A5") || g.startsWith("A6") || g.startsWith("B2")
                || g.startsWith("B4") || id.equals("B-21") || id.equals("B-26") || id.equals("X-81") || id.equals("R-60");
    }

    /** Re-run command for this single case (no browser flag: the browser is visible; add -DHeadless=true to hide it). */
    public static String runCommand(ResultCollector.Case c) {
        if (c.group != null && c.group.startsWith("C5 API health (observed")) {
            return "Evidence from earlier happy-flow executions; re-run = the happy flow (" + base(HAPPY_XML) + ", creates 1 client) and read api-evidence/network-summary.json";
        }
        if (happy(c)) {
            return base(HAPPY_XML) + "    (creates 1 client; all E2E cases of the class run in order, the client is created by TC-E2E-01. "
                    + "To re-check the profile of an EXISTING client without creating one: add -Dhappy.client.data=reports/<happy-flow folder>/test-data.json)";
        }
        String cmd = base(EDGE_XML) + " -Dcases=" + c.id;
        if (SIDE_EFFECT.contains(c.id)) {
            return cmd + "    (creates 1 EXTRA client if the application accepts the input; skipped in a full run because of the 2-client rule)";
        }
        if (needsEdgeClient(c)) {
            return cmd + "    (set-up steps register the edge client first; to reuse an existing one add -Dedge.client.email=<yopmail address> "
                    + "and put its password in env KOMP_EDGE_CLIENT_PASSWORD; the first-login password cases need a client that never logged in)";
        }
        return cmd;
    }

    private static String base(String xml) {
        return "mvn clean test -Dsuite=" + xml;
    }

    /** Does running this case create a client in the sandbox? */
    public static String createsClient(ResultCollector.Case c) {
        if (c.group != null && c.group.startsWith("C5 API health (observed")) {
            return "Only if re-run (the happy flow creates 1 client)";
        }
        if (happy(c)) {
            return "Yes - 1 client (TC-E2E-01); none when -Dhappy.client.data is used";
        }
        if (SIDE_EFFECT.contains(c.id)) {
            return "Only if the application accepts the input (then 1 extra client)";
        }
        if ("R-60".equals(c.id)) {
            return "Yes - 1 client (by design: it is THE edge client, registered with a double click)";
        }
        if (needsEdgeClient(c)) {
            return "Shares the 1 edge client of the run (registered by the set-up steps); none with -Dedge.client.email";
        }
        return "No";
    }
}
