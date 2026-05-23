<#import "template.ftl" as layout>
<#--
  Phase 14d / Pixel-1:1 — mfe-shell login form.

  Overrides keycloak.v2's stock login.ftl with a stripped-down layout that
  mirrors the GeoWealth P1 login page 1:1:

    * no visible field labels (placeholder text only: "username" / "password")
    * no Show/Hide password toggle
    * primary "Login" button immediately under the inputs
    * "Forgot Password?" link directly below the button, centered
    * social/IdP providers list hidden (the realm has the p1 broker for
      SP-init from the GeoWealth side; users typing username/password on
      this page should never see it)

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
      </div>
    </div>
  </#if>
</@layout.registrationLayout>
