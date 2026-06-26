package com.gw.keycloak.emailotp;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Custom authenticator that mirrors P1's existing email-based 6-digit OTP MFA
 * flow. On authenticate(): asks user-service to issue + persist a fresh token,
 * sends an email via KC's stock EmailTemplateProvider, renders the OTP entry
 * form. On action(): forwards the submitted code to user-service for verify.
 *
 * Conditional: the realm flow wires this authenticator under a CONDITION that
 * fires only when the federated UserModel's `mfaRequiredFlag` attribute is
 * `true` (set in the auth flow JSON).
 */
public class EmailOtpAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(EmailOtpAuthenticator.class);

    private static final String NOTE_TOKEN_SENT = "EMAIL_OTP_TOKEN_SENT";
    private static final String PARAM_TOKEN = "otp";
    private static final String USER_SVC_URL_PROP = "userServiceUrl";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        if (user == null) {
            context.failure(AuthenticationFlowError.UNKNOWN_USER);
            return;
        }
        String entityId = entityIdOf(user);
        if (entityId == null) {
            // Realm-local user (not federated) — skip MFA, no email-OTP backing store.
            context.success();
            return;
        }
        String userServiceUrl = configValue(context, USER_SVC_URL_PROP, "http://user-service:8080");
        UserServiceClient client = new UserServiceClient(userServiceUrl);
        Optional<UserServiceClient.IssuedToken> issued = client.issue(entityId);
        if (issued.isEmpty()) {
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
            return;
        }
        UserServiceClient.IssuedToken tok = issued.get();
        sendEmail(context.getSession(), context.getRealm(), user, tok);
        context.getAuthenticationSession().setAuthNote(NOTE_TOKEN_SENT, "1");
        Response challenge = renderOtpForm(context, null, tok.sentTo());
        context.challenge(challenge);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        if (user == null) {
            context.failure(AuthenticationFlowError.UNKNOWN_USER);
            return;
        }
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        String submitted = form.getFirst(PARAM_TOKEN);
        if (submitted == null || submitted.isBlank()) {
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    renderOtpForm(context, "Code required", null));
            return;
        }
        String entityId = entityIdOf(user);
        String userServiceUrl = configValue(context, USER_SVC_URL_PROP, "http://user-service:8080");
        boolean ok = new UserServiceClient(userServiceUrl).verify(entityId, submitted.trim());
        if (ok) {
            context.success();
        } else {
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    renderOtpForm(context, "Invalid or expired code", null));
        }
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        // The realm's flow condition gates this — return true so KC will call us
        // when reached. The condition (mfaRequiredFlag=true) is evaluated by a
        // sibling ConditionalAuthenticator in the flow JSON.
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // No required actions — the user has no setup to perform; tokens are
        // issued on demand.
    }

    @Override
    public void close() {
    }

    private Response renderOtpForm(AuthenticationFlowContext context, String error, String sentTo) {
        LoginFormsProvider forms = context.form();
        Map<String, Object> attrs = new HashMap<>();
        if (sentTo != null) attrs.put("emailHint", maskEmail(sentTo));
        forms.setAttribute("emailOtp", attrs);
        if (error != null) forms.setError(error);
        return forms.createForm("login-otp.ftl");
    }

    private void sendEmail(KeycloakSession session, RealmModel realm, UserModel user, UserServiceClient.IssuedToken tok) {
        String address = user.getEmail();
        if (address == null || address.isBlank()) address = tok.sentTo();
        if (address == null || address.isBlank()) {
            LOG.warnf("OTP email: no address for user %s", user.getId());
            return;
        }
        String subject = "Your GeoWealth verification code";
        String text = "Your verification code is: " + tok.token() + "\nIt expires in 5 minutes.";
        String html = "<p>Your verification code is: <strong>" + tok.token() + "</strong></p>"
                + "<p>It expires in 5 minutes.</p>";
        try {
            // Direct send via EmailSenderProvider — bypasses theme templates so we
            // don't need a separate email-otp.ftl in the theme. KC reads SMTP
            // config from realm settings (configured via realm-export or admin UI).
            EmailSenderProvider sender = session.getProvider(EmailSenderProvider.class);
            sender.send(realm.getSmtpConfig(), address, subject, text, html);
        } catch (EmailException e) {
            LOG.warnf(e, "Could not send OTP email to %s", address);
            // Don't fail the authn — token is in DB; if SMTP is misconfigured
            // operators can still help the user complete login.
        }
    }

    private String entityIdOf(UserModel user) {
        // For federated users the KC id is "f:<provider-uuid>:<external-id>"; we
        // want the external entityId only. UserModel#getId returns the storage
        // id, so split on ':' and take the last segment.
        String kcId = user.getId();
        int last = kcId.lastIndexOf(':');
        return last < 0 ? kcId : kcId.substring(last + 1);
    }

    private String configValue(AuthenticationFlowContext context, String key, String fallback) {
        if (context.getAuthenticatorConfig() != null && context.getAuthenticatorConfig().getConfig() != null) {
            String v = context.getAuthenticatorConfig().getConfig().get(key);
            if (v != null && !v.isBlank()) return v;
        }
        return fallback;
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return "*" + domain;
        return local.charAt(0) + "*".repeat(Math.max(1, local.length() - 2)) + local.charAt(local.length() - 1) + domain;
    }
}
