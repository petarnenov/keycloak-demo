<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "form">
        <h1 class="auth-spa__title">Reset your password</h1>
        <p class="auth-spa__hint">Enter your username and we'll email you a reset link.</p>
        <form id="kc-form-reset" action="${url.loginAction}" method="post" class="auth-spa__form">
            <label class="auth-spa__field">
                <span>Username</span>
                <input id="username" name="username" type="text" autocomplete="username" autofocus
                       value="${(auth.attemptedUsername!'')}" />
            </label>
            <button type="submit" class="auth-spa__btn auth-spa__btn--primary">Send link</button>
            <a href="${url.loginUrl}" class="auth-spa__link">Back to sign in</a>
        </form>
    </#if>
</@layout.registrationLayout>
