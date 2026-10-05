import type { ParticipantStatus, PaymentStatus } from "../api/types";

const PAYMENT: Record<PaymentStatus, string> = {
  ACCEPTED: "bg-sky-50 text-sky-800 border-sky-200",
  PROCESSING: "bg-amber-50 text-amber-800 border-amber-200",
  COMPLETED: "bg-emerald-50 text-emerald-800 border-emerald-200",
  FAILED: "bg-rose-50 text-rose-800 border-rose-200",
  PENDING: "bg-slate-100 text-slate-700 border-slate-200",
};

const PARTICIPANT: Record<ParticipantStatus, string> = {
  ACTIVE: "bg-emerald-50 text-emerald-800 border-emerald-200",
  INACTIVE: "bg-slate-100 text-slate-600 border-slate-200",
};

const BREAKER: Record<string, string> = {
  CLOSED: "bg-emerald-50 text-emerald-800 border-emerald-200",
  OPEN: "bg-rose-50 text-rose-800 border-rose-200",
  HALF_OPEN: "bg-amber-50 text-amber-800 border-amber-200",
};

function pill(className: string, label: string) {
  return (
    <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs font-medium ${className}`}>{label}</span>
  );
}

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  return pill(PAYMENT[status], status);
}

export function ParticipantStatusBadge({ status }: { status: ParticipantStatus }) {
  return pill(PARTICIPANT[status], status);
}

export function CircuitBreakerBadge({ state }: { state: string }) {
  return pill(BREAKER[state] ?? "bg-slate-100 text-slate-700 border-slate-200", state);
}

export function TypeBadge({ type }: { type: string }) {
  return pill("bg-slate-50 text-slate-700 border-slate-200", type.replaceAll("_", " "));
}
