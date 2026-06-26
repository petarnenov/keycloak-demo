<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "form">
        <h1 class="auth-spa__title">Verify your identity</h1>
        <#assign sent = (emailOtp.emailHint)!''>
        <p class="auth-spa__hint">
            <#if sent != ''>We emailed a 6-digit code to ${sent}.<#else>We emailed you a 6-digit code.</#if>
        </p>
        <form id="kc-form-otp" action="${url.loginAction}" method="post" class="auth-spa__form">
            <label class="auth-spa__field">
                <span>Code</span>
                <input id="otp" name="otp" type="text" inputmode="numeric" maxlength="6"
                       autocomplete="one-time-code" autofocus pattern="[0-9]{6}" />
            </label>
            <button type="submit" class="auth-spa__btn auth-spa__btn--primary">Verify</button>
        </form>
    </#if>
</@layout.registrationLayout>
