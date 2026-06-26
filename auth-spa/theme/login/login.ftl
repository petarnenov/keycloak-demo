<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "form">
        <h1 class="auth-spa__title">Sign in</h1>
        <form id="kc-form-login" action="${url.loginAction}" method="post" class="auth-spa__form">
            <label class="auth-spa__field">
                <span>Username</span>
                <input tabindex="1" id="username" name="username" type="text"
                       autocomplete="username" autofocus
                       value="${(login.username!'')}" />
            </label>
            <label class="auth-spa__field">
                <span>Password</span>
                <input tabindex="2" id="password" name="password" type="password"
                       autocomplete="current-password" />
            </label>
            <#if realm.rememberMe && !usernameHidden??>
                <label class="auth-spa__check">
                    <input type="checkbox" name="rememberMe" <#if login.rememberMe??>checked</#if>/>
                    <span>${msg("rememberMe")}</span>
                </label>
            </#if>
            <button tabindex="3" type="submit" class="auth-spa__btn auth-spa__btn--primary">Sign in</button>
            <#if realm.resetPasswordAllowed>
                <a tabindex="4" href="${url.loginResetCredentialsUrl}" class="auth-spa__link">Forgot password?</a>
            </#if>
        </form>
    </#if>
</@layout.registrationLayout>
