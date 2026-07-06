package demo.billing;

import io.micronaut.runtime.Micronaut;

/**
 * Entry point for the billing data BFF. A plain Micronaut app — it boots only
 * the billing controller + the thin {@code domain-sdk} beans (PolicyRuleGate /
 * PolicyRuleClient). It is auth-UNAWARE (forward-auth): identity arrives as
 * {@code X-Auth-*} headers, so there is no session, login, logout or token
 * refresh here (that all lives in the token-handler).
 */
public final class Application {
    private Application() {
    }

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
