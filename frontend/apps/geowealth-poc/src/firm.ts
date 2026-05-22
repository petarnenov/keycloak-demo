/**
 * The POC's tenant-resolution rule: the browser-visible hostname's leading
 * subdomain is the firm code. Falls back to "default" when no recognizable
 * subdomain is present (plain localhost / 127.0.0.1).
 *
 * The same hostname is what Keycloak sees in the Host header on the next
 * request hop, so Phase 2's ThemeSelectorProvider can mirror this rule on
 * the server side without separate signalling.
 */
export function firmFromHostname(hostname: string): string {
  const host = hostname.toLowerCase();
  if (host === 'localhost' || host === '127.0.0.1') return 'default';
  const dot = host.indexOf('.');
  if (dot <= 0) return 'default';
  return host.substring(0, dot);
}

const FIRM_DISPLAY_NAMES: Record<string, string> = {
  changepath: 'ChangePath',
  default: 'GeoWealth'
};

export function firmDisplayName(code: string): string {
  return FIRM_DISPLAY_NAMES[code] ?? code;
}
