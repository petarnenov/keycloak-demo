package demo.custodian;

import io.micronaut.runtime.Micronaut;

/**
 * Entry point for the custodian data BFF. A plain Micronaut app — it boots only the
 * custodian controller + the thin {@code domain-sdk} beans (PolicyRuleGate /
 * PolicyRuleClient). Auth-UNAWARE (forward-auth): identity arrives as
 * {@code X-Auth-*} headers; no session, login, logout or token refresh here.
 */
public final class Application {
    private Application() {
    }

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
