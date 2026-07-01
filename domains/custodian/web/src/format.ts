// Shared display formatter. Both pages render monetary values, so keep this
// in one place rather than re-declaring it per page.

export function money(amount: number, currency = 'USD'): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount);
}
