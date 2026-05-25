// Shared display formatters. Both pages render monetary values and the
// portfolio view renders signed percentages, so keep these in one place
// rather than re-declaring per page.

export function money(amount: number, currency = 'USD'): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount);
}

export function pct(value: number): string {
  const sign = value >= 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}%`;
}
