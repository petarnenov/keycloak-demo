<#import "template.ftl" as layout>
<#--
  Phase 14d / Pixel-1:1 — mfe-shell login form.

  Overrides keycloak.v2's stock login.ftl with a stripped-down layout that
  mirrors the GeoWealth P1 login page 1:1:

    * no visible field labels (placeholder text only: "username" / "password")
    * no Show/Hide password toggle
    * primary "Login" button immediately under the inputs
    * "Forgot Password?" link directly below the button, centered
    * social/IdP providers list rendered as a secondary CTA  needed
      so SAML-federated users (e.g. tim1 routed through the P1 broker)
      have a UI entry point. Without it the only way to sign in is the
      username/password form, which a federated user can never satisfy
      because their credentials live in P1, not in Keycloak.

  All FreeMarker variables in scope are the ones Keycloak's
  LoginFormsProvider injects for ``login`` ftls: ``url.*``, ``realm.*``,
  ``messagesPerField``, ``login.*``, ``social.*``, ``auth.*``. The
  registrationLayout macro lives in template.ftl, which carries the brand
  injection ($\{brand}) so this override stays brand-aware too.
-->
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=false; section>
  <#if section = "header">
    ${msg("loginAccountTitle")}
  <#elseif section = "form">
    <div id="kc-form">
      <div id="kc-form-wrapper">
        <#if realm.password>
          <form id="kc-form-login" class="pf-v5-c-form" onsubmit="login.disabled = true; return true;"
                action="${url.loginAction}" method="post" novalidate="novalidate">

            <#-- USERNAME (placeholder-only, no visible label).
                 ``usernameHidden??`` would be true on re-auth where Keycloak
                 wants to keep the username locked; we still render the
                 input so layout doesn't shift, just disabled. -->
            <div class="pf-v5-c-form__group geowealth-form-row">
              <label for="username" class="pf-v5-c-form__label pf-v5-u-screen-reader">
                <span class="pf-v5-c-form__label-text">${msg("usernameOrEmail")}</span>
              </label>
              <span class="pf-v5-c-form-control">
                <input id="username" name="username" type="text" autocomplete="username" autofocus
                       placeholder="username"
                       value="${(login.username!'')}"
                       aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>" />
              </span>
              <#if messagesPerField.existsError('username','password')>
                <span id="input-error-username" class="pf-v5-c-form__helper-text pf-m-error" aria-live="polite">
                  ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                </span>
              </#if>
            </div>

            <#-- PASSWORD — same shape; no Show/Hide button (Phase 13c). -->
            <div class="pf-v5-c-form__group geowealth-form-row">
              <label for="password" class="pf-v5-c-form__label pf-v5-u-screen-reader">
                <span class="pf-v5-c-form__label-text">${msg("password")}</span>
              </label>
              <span class="pf-v5-c-form-control">
                <input id="password" name="password" type="password" autocomplete="current-password"
                       placeholder="password"
                       aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>" />
              </span>
            </div>

            <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>

            <div class="pf-v5-c-form__group">
              <div class="pf-v5-c-form__actions">
                <button class="pf-v5-c-button pf-m-primary pf-m-block" name="login" id="kc-login" type="submit">
                  ${msg("doLogIn")}
                </button>
              </div>
            </div>

            <#-- Forgot password link  visible only when the realm allows
                 self-service password reset. ``realm.resetPasswordAllowed``
                 is a boolean exposed by Keycloak's login form provider;
                 ``url.loginResetCredentialsUrl`` is the canonical target. -->
            <#if realm.resetPasswordAllowed>
              <div id="kc-form-options" class="geowealth-form-options">
                <a id="forgot-password" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
              </div>
            </#if>

          </form>
        </#if>

        <#-- Social / IdP providers (P1 SAML broker for the demo realm).
             Keycloak's stock login.ftl renders these inside a
             ``socialProviders`` section under the footer; we render them
             inline so they sit right under the Login button and read as
             a real alternative CTA, not a footnote. -->
        <#if realm.password && social?? && social.providers?? && social.providers?has_content>
          <div id="kc-social-providers" class="kc-social-section kc-social-gray">
            <ul class="pf-v5-c-login__main-footer-links">
              <#list social.providers as p>
                <li class="pf-v5-c-login__main-footer-links-item">
                  <a id="social-${p.alias}" class="pf-v5-c-login__main-footer-links-item-link"
                     href="${p.loginUrl}" aria-label="${p.displayName!p.alias}">
                    <#if p.iconClasses?has_content><i class="${p.iconClasses}" aria-hidden="true"></i></#if>
                    <span class="kc-social-provider-name">${msg("doSignInWith")} ${p.displayName!p.alias}</span>
                  </a>
                </li>
              </#list>
            </ul>
          </div>
        </#if>
      </div>
    </div>
  </#if>
</@layout.registrationLayout>
