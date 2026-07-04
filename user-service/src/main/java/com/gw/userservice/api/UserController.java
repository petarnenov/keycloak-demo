package com.gw.userservice.api;

import com.gw.userservice.dao.EntityDao;
import com.gw.userservice.dao.MembershipDao;
import com.gw.userservice.dao.MfaTokenDao;
import com.gw.userservice.dao.RoleDao;
import com.gw.userservice.domain.Membership;
import com.gw.userservice.domain.MfaTokenIssued;
import com.gw.userservice.domain.MfaVerifyRequest;
import com.gw.userservice.domain.MfaVerifyResponse;
import com.gw.userservice.domain.User;
import com.gw.userservice.domain.VerifyRequest;
import com.gw.userservice.domain.VerifyResponse;
import com.gw.userservice.security.SHAPassword;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller("/users")
public class UserController {

    private final EntityDao entities;
    private final RoleDao roles;
    private final MembershipDao memberships;
    private final MfaTokenDao mfa;
    private final int mfaTokenTtlSeconds;

    public UserController(
            EntityDao entities,
            RoleDao roles,
            MembershipDao memberships,
            MfaTokenDao mfa,
            @io.micronaut.context.annotation.Value("${mfa.token-ttl-seconds:300}") int mfaTokenTtlSeconds
    ) {
        this.entities = entities;
        this.roles = roles;
        this.memberships = memberships;
        this.mfa = mfa;
        this.mfaTokenTtlSeconds = mfaTokenTtlSeconds;
    }

    @Get("/search")
    public HttpResponse<User> searchByUsernameAndFirm(
            @QueryValue String username,
            @QueryValue(value = "firmCd") @Nullable Integer firmCd
    ) {
        if (firmCd != null) {
            return entities.findByUsernameAndFirm(username, firmCd)
                    .map(this::enrich)
                    .map(HttpResponse::ok)
                    .orElse(HttpResponse.notFound());
        }
        List<User> matches = entities.searchByUsername(username);
        if (matches.isEmpty()) return HttpResponse.notFound();
        return HttpResponse.ok(enrich(matches.get(0)));
    }

    @Get("/{id}")
    public HttpResponse<User> findById(@PathVariable String id) {
        return entities.findByEntityId(id)
                .map(this::enrich)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Get("/{id}/attributes")
    public HttpResponse<Map<String, Object>> attributes(@PathVariable String id) {
        return entities.findByEntityId(id)
                .map(u -> {
                    User enriched = enrich(u);
                    Map<String, Object> attrs = new LinkedHashMap<>();
                    attrs.put("ldapUid", enriched.ldapUid());
                    attrs.put("firmCd", enriched.firmCd());
                    attrs.put("memberships", enriched.memberships());
                    attrs.put("gwAdmin", enriched.gwAdminFlag());
                    attrs.put("personId", enriched.personId());
                    attrs.put("email", enriched.email());
                    attrs.put("firstName", enriched.firstName());
                    attrs.put("lastName", enriched.lastName());
                    attrs.put("mfaRequiredFlag", Boolean.TRUE.equals(enriched.mfaRequiredFlag()));
                    // loginRoles feeds the realm `roles-claim` protocol mapper
                    // (oidc-usermodel-attribute-mapper, user.attribute=loginRoles),
                    // so the SPI-federated user gets a top-level `roles` claim the
                    // token-handler reads. Mirrors the /roles endpoint.
                    List<String> loginRoles = new java.util.ArrayList<>(roles.findRoleNamesByEntityId(id));
                    if (enriched.gwAdminFlag()) loginRoles.add("gwAdmin");
                    attrs.put("loginRoles", loginRoles);
                    return HttpResponse.ok(attrs);
                })
                .orElse(HttpResponse.notFound());
    }

    @Get("/{id}/roles")
    public HttpResponse<List<String>> roles(@PathVariable String id) {
        Optional<User> user = entities.findByEntityId(id);
        if (user.isEmpty()) return HttpResponse.notFound();
        List<String> realmRoles = new java.util.ArrayList<>(roles.findRoleNamesByEntityId(id));
        if (user.get().gwAdminFlag()) realmRoles.add("gwAdmin");
        return HttpResponse.ok(realmRoles);
    }

    @Post("/{id}/verify-credentials")
    public HttpResponse<VerifyResponse> verifyCredentials(@PathVariable String id, @Body @Valid VerifyRequest body) {
        Optional<User> user = entities.findByEntityId(id);
        if (user.isEmpty()) return HttpResponse.ok(VerifyResponse.fail(VerifyResponse.UNKNOWN_USER));
        if (user.get().isLocked()) return HttpResponse.ok(VerifyResponse.fail(VerifyResponse.LOCKED));
        Optional<String> hash = entities.findPasswordHash(id);
        if (hash.isEmpty()) return HttpResponse.ok(VerifyResponse.fail(VerifyResponse.BAD_PASSWORD));
        try {
            boolean ok = new SHAPassword().check(body.password(), hash.get());
            return HttpResponse.ok(ok ? VerifyResponse.ok() : VerifyResponse.fail(VerifyResponse.BAD_PASSWORD));
        } catch (Exception e) {
            return HttpResponse.ok(VerifyResponse.fail(VerifyResponse.BAD_PASSWORD));
        }
    }

    @Post("/{id}/mfa-token")
    public HttpResponse<MfaTokenIssued> issueMfaToken(@PathVariable String id) {
        if (entities.findByEntityId(id).isEmpty()) return HttpResponse.notFound();
        String token = mfa.issueToken(id, mfaTokenTtlSeconds);
        // The actual email send is performed by KC's mail config; user-service
        // returns the destination + the plain token for the Authenticator SPI to
        // pass into KC's EmailTemplateProvider. (KC will not echo the token
        // back to the user; it puts it into the rendered email body.)
        String to = mfa.findEmail(id).orElse(null);
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        body.put("tokenSentTo", to);
        return HttpResponse.ok(new MfaTokenIssued(to, mfaTokenTtlSeconds));
    }

    @Post("/{id}/mfa-token/verify")
    public HttpResponse<MfaVerifyResponse> verifyMfaToken(@PathVariable String id, @Body @Valid MfaVerifyRequest body) {
        return HttpResponse.ok(mfa.verifyToken(id, body.token())
                ? MfaVerifyResponse.ok()
                : MfaVerifyResponse.fail("INVALID_OR_EXPIRED"));
    }

    private User enrich(User u) {
        List<String> mems = memberships.findByEntityId(u.entityId()).stream()
                .map(Membership::toClaim)
                .toList();
        String personRoot = memberships.findPersonRoot(u.entityId());
        return new User(
                u.entityId(), u.firmCd(), u.ldapUid(), u.entityTypeCd(),
                u.entityActiveFlag(), u.loginInactivatedFlag(), u.loginInactivedReasonCd(),
                u.gwAdminFlag(), u.linkedGwUser(), u.mfaRequiredFlag(),
                // email/first/last not in ENTITY_TBL in demo schema — placeholder.
                // lastName mirrors ldapUid so KC's declarative user profile
                // (which marks lastName required by default) accepts the
                // federated user without forcing an Update Account form on
                // first login.
                "redacted-" + u.entityId().substring(0, 6) + "@geowealth.local",
                u.ldapUid(), u.ldapUid(),
                personRoot,
                mems,
                u.extras() == null ? new HashMap<>() : u.extras()
        );
    }
}
