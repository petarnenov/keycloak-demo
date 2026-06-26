<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "form">
        <#-- "Sign in" heading + form, mirroring P1's LoginTemplate1 + UsernamePassword.
             Inputs use the floating-legend pattern: the <legend> sits transparent
             until the input is non-empty, then floats up and paints over the
             fieldset border (CSS in resources/css/styles.css). -->
        <h2 class="auth-spa__title">Sign in</h2>
        <form id="kc-form-login" action="${url.loginAction}" method="post" class="auth-spa__form">
            <fieldset class="auth-spa__field">
                <input tabindex="1" id="username" name="username" type="text"
                       placeholder="username"
                       autocomplete="username" autofocus
                       value="${(login.username!'')}" />
                <legend>username</legend>
            </fieldset>
            <fieldset class="auth-spa__field">
                <input tabindex="2" id="password" name="password" type="password"
                       placeholder="password"
                       autocomplete="current-password" />
                <legend>password</legend>
            </fieldset>
            <#if realm.rememberMe && !usernameHidden??>
                <label class="auth-spa__check">
                    <input type="checkbox" name="rememberMe" <#if login.rememberMe??>checked</#if>/>
                    <span>${msg("rememberMe")}</span>
                </label>
            </#if>
            <button tabindex="3" type="submit" class="auth-spa__btn auth-spa__btn--primary">Login</button>
            <#if realm.resetPasswordAllowed>
                <a tabindex="4" href="${url.loginResetCredentialsUrl}" class="auth-spa__link">Forgot Password?</a>
            </#if>
        </form>

        <#-- Federated IdP block ("Or sign in with → P1") intentionally NOT
             rendered. The realm's `p1` SAML IdP was retired in Phase 5
             (struts-saml-idp.xml maps only Tier 2/3 authz endpoints now);
             leaving the brokered-login button in the UI dead-ends users on
             a /saml/idp/sso.do that 302s back to /react/indexReact.do. KC
             authenticates against the user-storage SPI directly — no
             broker, no SAML, single primary action. -->
    </#if>
</@layout.registrationLayout>
