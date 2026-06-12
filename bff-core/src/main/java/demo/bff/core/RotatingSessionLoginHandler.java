package demo.bff.core;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.config.RedirectConfiguration;
import io.micronaut.security.config.RedirectService;
import io.micronaut.security.errors.PriorToLoginPersistence;
import io.micronaut.security.session.SessionLoginHandler;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import io.micronaut.session.http.SessionForRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Session-fixation defence (M4) for the BFF / Token Handler login.
 *
 * <p>micronaut-security-session's stock {@link SessionLoginHandler} reuses the
 * pre-authentication session — the one created to hold the OAuth {@code state} /
 * {@code nonce} / PKCE verifier during the authorization-code redirect — and just
 * drops the {@link Authentication} into it. The session id therefore survives the
 * login unchanged, so an attacker who can plant a {@code BSESSION} cookie before
 * login (e.g. via a sibling host setting a {@code Domain=.geowealth.int} cookie)
 * could fix a session id and ride the victim's authenticated session afterwards.</p>
 *
 * <p>This replacement rotates the session id on successful authentication: it
 * issues a brand-new session, makes it the request's session (so the session
 * filter emits a fresh {@code Set-Cookie}), and deletes the old one. The pre-auth
 * session only held the now-consumed OAuth transients — {@code state}/{@code nonce}
 * were already validated by the time this runs — so nothing needs carrying over;
 * the parent's {@code loginSuccess} then stores the {@link Authentication} in the
 * fresh session and builds the usual redirect.</p>
 */
@Singleton
@Replaces(SessionLoginHandler.class)
public class RotatingSessionLoginHandler extends SessionLoginHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RotatingSessionLoginHandler.class);

    public RotatingSessionLoginHandler(RedirectConfiguration redirectConfiguration,
                                       SessionStore<Session> sessionStore,
                                       @Nullable PriorToLoginPersistence<HttpRequest<?>, MutableHttpResponse<?>> priorToLoginPersistence,
                                       RedirectService redirectService) {
        super(redirectConfiguration, sessionStore, priorToLoginPersistence, redirectService);
    }

    @Override
    public MutableHttpResponse<?> loginSuccess(Authentication authentication, HttpRequest<?> request) {
        rotateSession(request);
        // super.loginSuccess() now finds the freshly-created session in the request
        // (SessionForRequest.find) and stores the Authentication in it.
        return super.loginSuccess(authentication, request);
    }

    private void rotateSession(HttpRequest<?> request) {
        Optional<Session> existing = SessionForRequest.find(request);
        if (existing.isEmpty()) {
            // No pre-auth session to rotate; the parent will create a fresh one.
            return;
        }
        String oldId = existing.get().getId();
        // Create a new session (new id) and bind it to the request so the session
        // filter writes a new cookie and the parent stores the Authentication here.
        Session fresh = SessionForRequest.create(sessionStore, request);
        if (!fresh.getId().equals(oldId)) {
            try {
                sessionStore.deleteSession(oldId);
            } catch (Exception ignored) {
                // best-effort: the old session may already be gone
            }
            LOG.debug("rotated session id on login (fixation defence)");
        }
    }
}
