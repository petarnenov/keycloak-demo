<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "form">
        <h1 class="auth-spa__title">Something went wrong</h1>
        <p class="auth-spa__hint">${message.summary?no_esc}</p>
        <#if client?? && client.baseUrl??>
            <a href="${client.baseUrl}" class="auth-spa__btn auth-spa__btn--primary">Return to ${client.name!client.clientId}</a>
        </#if>
    </#if>
</@layout.registrationLayout>
