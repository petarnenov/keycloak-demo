package com.gw.keycloak.userstorage;

import org.keycloak.component.ComponentModel;
import org.keycloak.credential.UserCredentialManager;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.adapter.AbstractUserAdapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Adapter that wraps a user-service `User` JSON blob and exposes it as a
 * Keycloak UserModel. AbstractUserAdapter does most of the boilerplate;
 * we only override the lookups we want to surface from user-service:
 * username, email, first/last name, custom attributes, realm role names.
 */
public class UserServiceUser extends AbstractUserAdapter {

    private final String entityId;
    private final String username;
    private final Map<String, Object> attributes;
    private final List<String> realmRoleNames;

    public UserServiceUser(
            KeycloakSession session,
            RealmModel realm,
            ComponentModel storageProviderModel,
            String entityId,
            String username,
            Map<String, Object> attributes,
            List<String> realmRoleNames
    ) {
        super(session, realm, storageProviderModel);
        this.entityId = entityId;
        this.username = username;
        this.attributes = attributes == null ? new LinkedHashMap<>() : attributes;
        this.realmRoleNames = realmRoleNames == null ? List.of() : realmRoleNames;
        this.storageId = new StorageId(storageProviderModel.getId(), entityId);
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getEmail() {
        Object v = attributes.get("email");
        return v == null ? null : v.toString();
    }

    @Override
    public String getFirstName() {
        Object v = attributes.get("firstName");
        return v == null ? null : v.toString();
    }

    @Override
    public String getLastName() {
        Object v = attributes.get("lastName");
        return v == null ? null : v.toString();
    }

    @Override
    public Stream<String> getAttributeStream(String name) {
        Object v = attributes.get(name);
        if (v == null) return Stream.empty();
        if (v instanceof List<?> l) return l.stream().map(Object::toString);
        return Stream.of(v.toString());
    }

    @Override
    public Map<String, List<String>> getAttributes() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : attributes.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                out.put(e.getKey(), List.of());
            } else if (v instanceof List<?> l) {
                out.put(e.getKey(), l.stream().map(Object::toString).toList());
            } else {
                out.put(e.getKey(), List.of(v.toString()));
            }
        }
        // KC convention: username is also an attribute.
        out.putIfAbsent("username", List.of(username));
        return out;
    }

    @Override
    protected Set<org.keycloak.models.RoleModel> getRoleMappingsInternal() {
        return realmRoleNames.stream()
                .map(name -> realm.getRole(name))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public String getEntityId() {
        return entityId;
    }

    // user-service is the source of truth. KC's Update Account / required-action
    // flows would otherwise hit AbstractUserAdapter.setFirstName/Last/Email,
    // which throws ReadOnlyException → HTTP 500. Swallow writes so the form
    // round-trips cleanly; persistence stays in Oracle via user-service.
    @Override
    public void setFirstName(String firstName) {
    }

    @Override
    public void setLastName(String lastName) {
    }

    @Override
    public void setEmail(String email) {
    }

    @Override
    public void setSingleAttribute(String name, String value) {
    }

    @Override
    public void setAttribute(String name, List<String> values) {
    }

    @Override
    public void removeAttribute(String name) {
    }

    @Override
    public SubjectCredentialManager credentialManager() {
        // Federated users must provide their own credential manager. The stock
        // UserCredentialManager delegates `isValid` to every
        // CredentialInputValidator registered for the realm — including this
        // provider's UserStorageProviderImpl, which calls user-service to verify
        // the SHA1+salt hash against Oracle.
        return new UserCredentialManager(session, realm, this);
    }
}
