import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Payment, PaymentStatus } from "../api/types";
import { AuditTimeline } from "../components/AuditTimeline";
import { DataTable } from "../components/DataTable";
import { Modal } from "../components/Modal";
import { PaymentStatusBadge } from "../components/StatusBadge";
import { formatMoney } from "../lib/format";
import { XmlViewer } from "../components/XmlViewer";

const STATUSES: PaymentStatus[] = ["ACCEPTED", "PROCESSING", "COMPLETED", "FAILED", "PENDING"];

type Tab = "pacs008" | "pacs002" | "audit";

export function TransactionsPage() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<PaymentStatus | "">("");
  const [participantId, setParticipantId] = useState("");
  const [reference, setReference] = useState("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [selected, setSelected] = useState<Payment | null>(null);
  const [tab, setTab] = useState<Tab>("audit");

  const participantsQuery = useQuery({ queryKey: ["participants"], queryFn: api.participants });
  const searchQuery = useQuery({
    queryKey: ["payments", page, status, participantId, reference, fromDate, toDate],
    queryFn: () =>
      api.searchPayments({
        page,
        size: 20,
        status,
        destinationParticipantId: participantId || undefined,
        reference: reference.trim() || undefined,
        fromDate: fromDate || undefined,
        toDate: toDate || undefined,
      }),
  });

  const codes = useMemo(() => {
    const map = new Map<string, string>();
    for (const participant of participantsQuery.data ?? []) {
      map.set(participant.id, participant.code);
    }
    return map;
  }, [participantsQuery.data]);

  const xml008 = useQuery({
    queryKey: ["pacs008", selected?.id],
    queryFn: () => api.pacs008(selected!.id),
    enabled: selected != null && tab === "pacs008",
  });
  const xml002 = useQuery({
    queryKey: ["pacs002", selected?.id],
    queryFn: () => api.pacs002(selected!.id),
    enabled: selected != null && tab === "pacs002",
  });
  const auditQuery = useQuery({
    queryKey: ["audit", selected?.id],
    queryFn: () => api.audit(selected!.id),
    enabled: selected != null && tab === "audit",
  });

  const pageModel = searchQuery.data?.page;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-slate-900">Transactions</h1>
        <p className="text-sm text-slate-500">Click a row to inspect ISO messages and the audit trail.</p>
      </div>

      <form
        className="grid gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm md:grid-cols-6"
        onSubmit={(event) => {
          event.preventDefault();
          setPage(0);
          void searchQuery.refetch();
        }}
      >
        <select
          className="rounded-md border border-slate-200 px-3 py-2 text-sm"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value as PaymentStatus | "");
            setPage(0);
          }}
        >
          <option value="">All statuses</option>
          {STATUSES.map((item) => (
            <option key={item} value={item}>
              {item}
            </option>
          ))}
        </select>
        <select
          className="rounded-md border border-slate-200 px-3 py-2 text-sm"
          value={participantId}
          onChange={(event) => {
            setParticipantId(event.target.value);
            setPage(0);
          }}
        >
          <option value="">Any destination</option>
          {(participantsQuery.data ?? []).map((participant) => (
            <option key={participant.id} value={participant.id}>
              {participant.code}
            </option>
          ))}
        </select>
        <input
          className="rounded-md border border-slate-200 px-3 py-2 text-sm"
          placeholder="Reference contains"
          value={reference}
          onChange={(event) => setReference(event.target.value)}
        />
        <input
          type="date"
          className="rounded-md border border-slate-200 px-3 py-2 text-sm"
          value={fromDate}
          onChange={(event) => setFromDate(event.target.value)}
        />
        <input
          type="date"
          className="rounded-md border border-slate-200 px-3 py-2 text-sm"
          value={toDate}
          onChange={(event) => setToDate(event.target.value)}
        />
        <button type="submit" className="rounded-md bg-teal-700 px-3 py-2 text-sm text-white hover:bg-teal-600">
          Search
        </button>
      </form>

      {searchQuery.isError ? (
        <p className="text-sm text-rose-700">
          {searchQuery.error instanceof Error ? searchQuery.error.message : "Search failed"}
        </p>
      ) : null}

      <DataTable
        columns={[
          { key: "ref", header: "Reference", render: (row) => <span className="font-mono">{row.paymentReference}</span> },
          {
            key: "route",
            header: "Route",
            render: (row) =>
              `${codes.get(row.senderParticipantId) ?? "?"} → ${
                row.destinationParticipantId ? (codes.get(row.destinationParticipantId) ?? "?") : "—"
              }`,
          },
          {
            key: "amount",
            header: "Amount",
            render: (row) => formatMoney(row.amount, row.currency),
          },
          { key: "status", header: "Status", render: (row) => <PaymentStatusBadge status={row.status} /> },
          {
            key: "created",
            header: "Created",
            render: (row) => new Date(row.createdAt).toLocaleString(),
          },
        ]}
        rows={searchQuery.data?.content ?? []}
        rowKey={(row) => row.id}
        onRowClick={(row) => {
          setSelected(row);
          setTab("audit");
        }}
        empty={searchQuery.isLoading ? "Loading…" : "No payments match the filters."}
        page={page}
        totalPages={pageModel?.totalPages ?? 0}
        totalElements={pageModel?.totalElements ?? 0}
        onPageChange={setPage}
      />

      <Modal
        open={selected != null}
        title={selected ? selected.paymentReference : "Payment"}
        onClose={() => setSelected(null)}
        wide
      >
        {selected ? (
          <div className="space-y-4">
            <dl className="grid gap-3 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-xs text-slate-500">Amount</dt>
                <dd>{formatMoney(selected.amount, selected.currency)}</dd>
              </div>
              <div>
                <dt className="text-xs text-slate-500">Status</dt>
                <dd className="mt-1">
                  <PaymentStatusBadge status={selected.status} />
                </dd>
              </div>
              <div>
                <dt className="text-xs text-slate-500">UETR</dt>
                <dd className="font-mono text-xs">{selected.uetr ?? "—"}</dd>
              </div>
            </dl>
            {selected.failureReason ? (
              <p className="rounded-md bg-rose-50 px-3 py-2 text-sm text-rose-800">{selected.failureReason}</p>
            ) : null}

            <div className="flex gap-2 border-b border-slate-200">
              {(
                [
                  ["audit", "Audit"],
                  ["pacs008", "pacs.008"],
                  ["pacs002", "pacs.002"],
                ] as const
              ).map(([id, label]) => (
                <button
                  key={id}
                  type="button"
                  className={`-mb-px border-b-2 px-3 py-2 text-sm ${
                    tab === id ? "border-teal-700 font-medium text-teal-800" : "border-transparent text-slate-500"
                  }`}
                  onClick={() => setTab(id)}
                >
                  {label}
                </button>
              ))}
            </div>

            {tab === "audit" ? (
              auditQuery.isLoading ? (
                <p className="text-sm text-slate-500">Loading audit…</p>
              ) : (
                <AuditTimeline entries={auditQuery.data ?? []} />
              )
            ) : null}
            {tab === "pacs008" ? (
              xml008.isLoading ? (
                <p className="text-sm text-slate-500">Loading pacs.008…</p>
              ) : xml008.data ? (
                <XmlViewer xml={xml008.data} label="pacs.008" />
              ) : (
                <p className="text-sm text-rose-700">Could not load pacs.008.</p>
              )
            ) : null}
            {tab === "pacs002" ? (
              xml002.isLoading ? (
                <p className="text-sm text-slate-500">Loading pacs.002…</p>
              ) : xml002.data ? (
                <XmlViewer xml={xml002.data} label="pacs.002" />
              ) : (
                <p className="text-sm text-rose-700">Could not load pacs.002.</p>
              )
            ) : null}
          </div>
        ) : null}
      </Modal>
    </div>
  );
}
