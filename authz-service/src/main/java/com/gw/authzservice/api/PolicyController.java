package com.gw.authzservice.api;

import com.gw.authzservice.dao.PolicyRuleDao;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gw.authzservice.domain.CanRequest;
import com.gw.authzservice.domain.RefineRequest;

import java.util.List;
import java.util.Map;

/**
 * The PolicyRule decision endpoints — the demo's {@code PolicyRuleManager} read
 * surface exposed over HTTP (alignment Phase A0). Owns the {@code /policy/*}
 * contract that P1's {@code p1-authz-*.do} actions used to serve.
 *
 * <ul>
 *   <li>{@code GET  /policy/capabilities} — the role-level {@code <objType>_<perm>}
 *       map (VIEW/CREATE/EXECUTE), master {@code loadCreateExecutePermissions}.
 *       A client-side UI hint; NOT the server gate.</li>
 *   <li>{@code POST /policy/can} — master {@code canUserDoObject} (single object
 *       when {@code objectId} is set, section/capability when it is null).</li>
 *   <li>{@code POST /policy/refine} — master {@code refineUUIDs} (page of ids →
 *       the allowed subset).</li>
 * </ul>
 *
 * <p>Authority is user-bound: the caller forwards the user's own Keycloak access
 * token, validated (JWKS signature + expiry) before this controller runs; the
 * entity is resolved from the token {@code sub} (never a query param, alignment
 * D3). gwAdmin is NOT applied here — it is a caller-side convention
 * ({@code gwAdmin || canX()}), matching master where {@code PolicyRuleManager}
 * itself never short-circuits (alignment §4.5).</p>
 */
@Controller("/policy")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class PolicyController {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyController.class);

    private final PolicyRuleDao policy;

    public PolicyController(PolicyRuleDao policy) {
        this.policy = policy;
    }

    @Get("/capabilities")
    public Map<String, Object> capabilities(Authentication authentication) {
        String entityId = PolicyRuleDao.entityIdFromSub(authentication.getName());
        Map<String, Boolean> permissions;
        try {
            permissions = policy.loadCreateExecutePermissions(entityId);
        } catch (Exception e) {
            LOG.warn("capabilities: load failed for entity; returning empty", e);
            permissions = Map.of();
        }
        return Map.of("permissions", permissions);
    }

    @Post("/can")
    public Map<String, Object> can(Authentication authentication, @Body @Valid CanRequest body) {
        String entityId = PolicyRuleDao.entityIdFromSub(authentication.getName());
        boolean allowed;
        try {
            allowed = policy.canDo(entityId, body.objectType(), body.permission(), body.objectId());
        } catch (Exception e) {
            LOG.warn("can: check failed; denying (fail closed)", e);
            allowed = false;
        }
        return Map.of("allowed", allowed);
    }

    @Post("/refine")
    public Map<String, Object> refine(Authentication authentication, @Body @Valid RefineRequest body) {
        String entityId = PolicyRuleDao.entityIdFromSub(authentication.getName());
        List<String> allowed;
        try {
            allowed = policy.refine(entityId, body.objectType(), body.permission(), body.ids());
        } catch (Exception e) {
            LOG.warn("refine: failed; denying all (fail closed)", e);
            allowed = List.of();
        }
        return Map.of("allowed", allowed);
    }
}
