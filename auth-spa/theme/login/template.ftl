<#macro registrationLayout displayMessage=true displayRequiredFields=false displayWide=false showAnotherWayIfPresent=true>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width,initial-scale=1.0">
    <title>${msg("loginTitle",(realm.displayName!''))?no_esc}</title>
    <link rel="icon" href="${url.resourcesPath}/img/favicon.ico" />
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
</head>
<body class="auth-spa">
<div class="auth-spa__shell">
    <header class="auth-spa__brand">
        <span class="auth-spa__logo">GW</span>
        <span class="auth-spa__name">GeoWealth Identity</span>
    </header>
    <main id="root" class="auth-spa__main">
        <#-- Plain-FTL form renders below; the React bundle (when built) hydrates
             into #root and replaces the static markup with the interactive SPA. -->
        <section class="auth-spa__card">
            <#if message?has_content && (messagesPerField.exists('global') || message.type != 'warning' || !isAppInitiatedAction??)>
                <div class="auth-spa__alert auth-spa__alert--${message.type}">${kcSanitize(message.summary)?no_esc}</div>
            </#if>
            <#nested "form">
        </section>
    </main>
    <footer class="auth-spa__foot">© GeoWealth</footer>
</div>
<#if properties.scripts?has_content>
    <#list properties.scripts?split(' ') as script>
        <script src="${url.resourcesPath}/${script}" defer></script>
    </#list>
</#if>
</body>
</html>
</#macro>
