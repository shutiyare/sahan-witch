import type { AuditEntry } from "../api/types";
import { PaymentStatusBadge } from "./StatusBadge";

export function AuditTimeline({ entries }: { entries: AuditEntry[] }) {
  if (entries.length === 0) {
    return <p className="text-sm text-slate-500">No audit rows for this payment.</p>;
  }

  return (
    <ol className="space-y-3">
      {entries.map((entry) => (
        <li key={entry.id} className="rounded-lg border border-slate-200 bg-slate-50 p-3">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span className="text-slate-500">{entry.previousStatus ?? "—"}</span>
            <span className="text-slate-400">→</span>
            <PaymentStatusBadge status={entry.newStatus} />
            <span className="ml-auto text-xs text-slate-500">
              {new Date(entry.createdAt).toLocaleString()}
            </span>
          </div>
          <p className="mt-2 text-sm text-slate-800">{entry.reason}</p>
          {entry.correlationId ? (
            <p className="mt-1 font-mono text-xs text-slate-500">corr {entry.correlationId}</p>
          ) : null}
        </li>
      ))}
    </ol>
  );
}
