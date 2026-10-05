export function formatCount(value: number): string {
  return new Intl.NumberFormat("en-US").format(value);
}

export function formatMoney(value: number | string, currency: string): string {
  const amount = typeof value === "number" ? value : Number(value);
  if (Number.isNaN(amount)) {
    return `${value} ${currency}`;
  }
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    maximumFractionDigits: 2,
  }).format(amount);
}

export function formatPercent(value: number | null | undefined): string {
  if (value == null) {
    return "—";
  }
  return `${value.toFixed(1)}%`;
}
