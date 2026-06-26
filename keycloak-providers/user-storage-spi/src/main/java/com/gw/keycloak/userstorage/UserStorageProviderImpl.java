package com.gw.keycloak.userstorage;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserQueryProvider;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class UserStorageProviderImpl implements
        UserStorageProvider,
        UserLookupProvider,
        UserQueryProvider,
        CredentialInputValidator {

    private static final Logger LOG = Logger.getLogger(UserStorageProviderImpl.class);

    private final KeycloakSession session;
    private final ComponentModel model;
    private final UserServiceClient client;

    public UserStorageProviderImpl(KeycloakSession session, ComponentModel model, UserServiceClient client) {
        this.session = session;
        this.model = model;
        this.client = client;
    }

    @Override
    public void close() {
    }

    // ---- UserLookupProvider ------------------------------------------------

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        StorageId sid = new StorageId(id);
        String entityId = sid.getExternalId();
        return loadByEntityId(realm, entityId);
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        Optional<Map<String, Object>> userJson = client.findByUsername(username);
        if (userJson.isEmpty()) return null;
        Object entityIdObj = userJson.get().get("entityId");
        if (entityIdObj == null) return null;
        return loadByEntityId(realm, entityIdObj.toString());
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        // user-service doesn't index by email today; the demo schema's email is
        // synthetic. Return null and KC will fall back to the realm's local
        // user search. A future "email search" route on user-service would
        // turn this into a real lookup.
        return null;
    }

    // ---- UserQueryProvider -------------------------------------------------
    // KC admin UI calls these. Phase 1 only implements username search; the
    // others return empty/zero. Adequate for the demo and for the admin UI's
    // "find user by username" workflow.

    @Override
    public int getUsersCount(RealmModel realm) {
        return 0;
    }

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, String search, Integer firstResult, Integer maxResults) {
        return searchByUsername(realm, search);
    }

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, Map<String, String> params, Integer firstResult, Integer maxResults) {
        String username = params.get(UserModel.USERNAME);
        if (username == null) username = params.get("search");
        if (username == null || username.isBlank()) return Stream.empty();
        return searchByUsername(realm, username);
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(RealmModel realm, GroupModel group, Integer firstResult, Integer maxResults) {
        return Stream.empty();
    }

    @Override
    public Stream<UserModel> searchForUserByUserAttributeStream(RealmModel realm, String attrName, String attrValue) {
        return Stream.empty();
    }

    // ---- CredentialInputValidator ------------------------------------------

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return PasswordCredentialModel.TYPE.equals(credentialType);
    }

    @Override
    public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
        return supportsCredentialType(credentialType) && isOurFederatedUser(user);
    }

    @Override
    public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType())) return false;
        if (!isOurFederatedUser(user)) return false;
        return client.verifyCredentials(entityIdOf(user), input.getChallengeResponse());
    }

    private boolean isOurFederatedUser(UserModel user) {
        if (user == null || user.getId() == null) return false;
        StorageId sid = new StorageId(user.getId());
        return model.getId().equals(sid.getProviderId());
    }

    private static String entityIdOf(UserModel user) {
        return new StorageId(user.getId()).getExternalId();
    }

    // ---- internals ---------------------------------------------------------

    private Stream<UserModel> searchByUsername(RealmModel realm, String username) {
        UserModel u = getUserByUsername(realm, username);
        return u == null ? Stream.empty() : Stream.of(u);
    }

    private UserModel loadByEntityId(RealmModel realm, String entityId) {
        Optional<Map<String, Object>> attrsOpt = client.attributes(entityId);
        if (attrsOpt.isEmpty()) return null;
        Map<String, Object> attrs = attrsOpt.get();
        String username = attrs.getOrDefault("ldapUid", "").toString();
        List<String> roles = client.roles(entityId);
        return new UserServiceUser(session, realm, model, entityId, username, attrs, roles);
    }
}
