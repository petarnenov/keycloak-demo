package demo.trading;

import io.micronaut.runtime.Micronaut;

/**
 * Entry point for the trading data BFF. A plain Micronaut app — it boots only the
 * trading controller + the thin {@code domain-sdk} beans (PolicyRuleGate /
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
