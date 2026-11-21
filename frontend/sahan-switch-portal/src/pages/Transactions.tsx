import { FormEvent, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { ApiError, type Payment, type PaymentStatus } from "../api/types";
import { AuditTimeline } from "../components/AuditTimeline";
import { DataTable } from "../components/DataTable";
import { Modal } from "../components/Modal";
import { PaymentStatusBadge } from "../components/StatusBadge";
import { formatMoney } from "../lib/format";
import { XmlViewer } from "../components/XmlViewer";

const STATUSES: PaymentStatus[] = ["ACCEPTED", "PROCESSING", "COMPLETED", "FAILED", "PENDING"];

type Tab = "pacs008" | "pacs002" | "audit";

function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

type PaymentFormState = {
  senderParticipantId: string;
  destinationParticipantId: string;
  sourceAccount: string;
  destinationAccount: string;
  amount: string;
  currency: string;
  endToEndId: string;
  debtorName: string;
  creditorName: string;
  idempotencyKey: string;
};

const emptyForm = (): PaymentFormState => ({
  senderParticipantId: "",
  destinationParticipantId: "",
  sourceAccount: "",
  destinationAccount: "",
  amount: "10.00",
  currency: "USD",
  endToEndId: "",
  debtorName: "",
  creditorName: "",
  idempotencyKey: newIdempotencyKey(),
});

export function TransactionsPage() {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<PaymentStatus | "">("");
  const [participantId, setParticipantId] = useState("");
  const [reference, setReference] = useState("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [selected, setSelected] = useState<Payment | null>(null);
  const [tab, setTab] = useState<Tab>("audit");

  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState<PaymentFormState>(() => emptyForm());
  const [formError, setFormError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitNotice, setSubmitNotice] = useState<string | null>(null);

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

  const activeParticipants = useMemo(
    () => (participantsQuery.data ?? []).filter((p) => p.status === "ACTIVE"),
    [participantsQuery.data],
  );

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

  // Keep the open payment fresh while async routing settles ACCEPTED → COMPLETED/FAILED
  const livePayment = useQuery({
    queryKey: ["payment", selected?.id],
    queryFn: () => api.payment(selected!.id),
    enabled: selected != null,
    refetchInterval: (query) => {
      const status = query.state.data?.status ?? selected?.status;
      return status === "ACCEPTED" || status === "PROCESSING" ? 2_000 : false;
    },
  });

  const detail = livePayment.data ?? selected;

  const createPayment = useMutation({
    mutationFn: () => {
      const currency = form.currency.trim().toUpperCase();
      return api.initiatePayment(
        {
          senderParticipantId: form.senderParticipantId,
          destinationParticipantId: form.destinationParticipantId,
          sourceAccount: form.sourceAccount.trim(),
          destinationAccount: form.destinationAccount.trim(),
          amount: form.amount.trim(),
          currency,
          endToEndId: form.endToEndId.trim() || null,
          debtorName: form.debtorName.trim() || null,
          creditorName: form.creditorName.trim() || null,
        },
        form.idempotencyKey.trim(),
      );
    },
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ["payments"] });
      void queryClient.invalidateQueries({ queryKey: ["analytics"] });
      setCreateOpen(false);
      setForm(emptyForm());
      setFormError(null);
      setFieldErrors({});
      setSubmitNotice(
        result.created
          ? `Created ${result.payment.paymentReference} (${result.payment.status}).`
          : `Replay: returned existing ${result.payment.paymentReference} (${result.payment.status}).`,
      );
      setSelected(result.payment);
      setTab("audit");
      void queryClient.invalidateQueries({ queryKey: ["payment", result.payment.id] });
      window.setTimeout(() => setSubmitNotice(null), 6000);
    },
    onError: (error) => {
      if (error instanceof ApiError) {
        setFormError(error.message);
        setFieldErrors(error.fieldErrors);
      } else {
        setFormError(error instanceof Error ? error.message : "Could not initiate payment");
        setFieldErrors({});
      }
    },
  });

  function openCreate() {
    setFormError(null);
    setFieldErrors({});
    setForm((current) => ({
      ...current,
      idempotencyKey: newIdempotencyKey(),
      senderParticipantId: current.senderParticipantId || activeParticipants[0]?.id || "",
      destinationParticipantId:
        current.destinationParticipantId ||
        activeParticipants.find((p) => p.id !== (current.senderParticipantId || activeParticipants[0]?.id))?.id ||
        "",
    }));
    setCreateOpen(true);
  }

  function onCreateSubmit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    setFieldErrors({});

    if (!form.senderParticipantId || !form.destinationParticipantId) {
      setFormError("Choose both sender and destination participants.");
      return;
    }
    if (form.senderParticipantId === form.destinationParticipantId) {
      setFormError("Sender and destination must be different.");
      return;
    }
    if (!form.idempotencyKey.trim()) {
      setFormError("Idempotency key is required.");
      return;
    }

    createPayment.mutate();
  }

  const pageModel = searchQuery.data?.page;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Transactions</h1>
          <p className="text-sm text-slate-500">
            Initiate payments here, then inspect ISO messages and the audit trail.
          </p>
        </div>
        <button
          type="button"
          className="shrink-0 rounded-md bg-teal-700 px-3 py-2 text-sm text-white hover:bg-teal-600"
          onClick={openCreate}
        >
          New payment
        </button>
      </div>

      {submitNotice ? (
        <p className="rounded-lg border border-teal-200 bg-teal-50 px-4 py-2 text-sm text-teal-900">{submitNotice}</p>
      ) : null}

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

      <Modal title="New payment" open={createOpen} onClose={() => setCreateOpen(false)} wide>
        <form className="space-y-4" onSubmit={onCreateSubmit}>
          <p className="text-sm text-slate-600">
            Creates a payment through the same API as Swagger. Async routing returns{" "}
            <span className="font-medium">ACCEPTED</span> first; refresh or reopen the row to see{" "}
            <span className="font-medium">COMPLETED</span> / <span className="font-medium">FAILED</span>. Prefix the
            destination account with <code className="text-xs">FAIL-</code> or{" "}
            <code className="text-xs">TIMEOUT-</code> to exercise those paths.
          </p>

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="block text-xs font-medium text-slate-500">
              Sender
              <select
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                value={form.senderParticipantId}
                onChange={(event) => setForm((f) => ({ ...f, senderParticipantId: event.target.value }))}
                required
              >
                <option value="">Select sender</option>
                {activeParticipants.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.code} — {p.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-xs font-medium text-slate-500">
              Destination
              <select
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                value={form.destinationParticipantId}
                onChange={(event) => setForm((f) => ({ ...f, destinationParticipantId: event.target.value }))}
                required
              >
                <option value="">Select destination</option>
                {activeParticipants.map((p) => (
                  <option key={p.id} value={p.id} disabled={p.id === form.senderParticipantId}>
                    {p.code} — {p.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-xs font-medium text-slate-500">
              Source account
              <input
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-mono"
                value={form.sourceAccount}
                onChange={(event) => setForm((f) => ({ ...f, sourceAccount: event.target.value }))}
                required
                maxLength={100}
                placeholder="615507298"
              />
              {fieldErrors.sourceAccount ? (
                <span className="mt-1 block text-rose-600">{fieldErrors.sourceAccount}</span>
              ) : null}
            </label>
            <label className="block text-xs font-medium text-slate-500">
              Destination account
              <input
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-mono"
                value={form.destinationAccount}
                onChange={(event) => setForm((f) => ({ ...f, destinationAccount: event.target.value }))}
                required
                maxLength={100}
                placeholder="628139911 or FAIL-… / TIMEOUT-…"
              />
              {fieldErrors.destinationAccount ? (
                <span className="mt-1 block text-rose-600">{fieldErrors.destinationAccount}</span>
              ) : null}
            </label>
            <label className="block text-xs font-medium text-slate-500">
              Amount
              <input
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                value={form.amount}
                onChange={(event) => setForm((f) => ({ ...f, amount: event.target.value }))}
                required
                inputMode="decimal"
              />
              {fieldErrors.amount ? <span className="mt-1 block text-rose-600">{fieldErrors.amount}</span> : null}
            </label>
            <label className="block text-xs font-medium text-slate-500">
              Currency
              <input
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm uppercase"
                value={form.currency}
                onChange={(event) => setForm((f) => ({ ...f, currency: event.target.value }))}
                required
                maxLength={3}
                minLength={3}
              />
              {fieldErrors.currency ? <span className="mt-1 block text-rose-600">{fieldErrors.currency}</span> : null}
            </label>
            <label className="block text-xs font-medium text-slate-500 sm:col-span-2">
              Idempotency key
              <div className="mt-1 flex gap-2">
                <input
                  className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-mono"
                  value={form.idempotencyKey}
                  onChange={(event) => setForm((f) => ({ ...f, idempotencyKey: event.target.value }))}
                  required
                />
                <button
                  type="button"
                  className="shrink-0 rounded-md border border-slate-200 px-3 py-2 text-xs hover:bg-slate-50"
                  onClick={() => setForm((f) => ({ ...f, idempotencyKey: newIdempotencyKey() }))}
                >
                  New key
                </button>
              </div>
              <span className="mt-1 block font-normal text-slate-400">
                Reuse the same key + same details to demo a 200 replay; change amount with the same key to get 409.
              </span>
            </label>
            <label className="block text-xs font-medium text-slate-500">
              End-to-end id (optional)
              <input
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                value={form.endToEndId}
                onChange={(event) => setForm((f) => ({ ...f, endToEndId: event.target.value }))}
                maxLength={35}
              />
            </label>
            <label className="block text-xs font-medium text-slate-500">
              Debtor name (optional)
              <input
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                value={form.debtorName}
                onChange={(event) => setForm((f) => ({ ...f, debtorName: event.target.value }))}
                maxLength={140}
              />
            </label>
            <label className="block text-xs font-medium text-slate-500 sm:col-span-2">
              Creditor name (optional)
              <input
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                value={form.creditorName}
                onChange={(event) => setForm((f) => ({ ...f, creditorName: event.target.value }))}
                maxLength={140}
              />
            </label>
          </div>

          {formError ? <p className="text-sm text-rose-700">{formError}</p> : null}

          <div className="flex justify-end gap-2 pt-2">
            <button type="button" className="rounded-md px-3 py-2 text-sm" onClick={() => setCreateOpen(false)}>
              Cancel
            </button>
            <button
              type="submit"
              disabled={createPayment.isPending || activeParticipants.length < 2}
              className="rounded-md bg-teal-700 px-3 py-2 text-sm text-white disabled:opacity-60"
            >
              {createPayment.isPending ? "Submitting…" : "Initiate payment"}
            </button>
          </div>
          {activeParticipants.length < 2 ? (
            <p className="text-xs text-amber-700">Need at least two ACTIVE participants. Register them first.</p>
          ) : null}
        </form>
      </Modal>

      <Modal
        open={selected != null}
        title={detail ? detail.paymentReference : "Payment"}
        onClose={() => setSelected(null)}
        wide
      >
        {detail ? (
          <div className="space-y-4">
            <dl className="grid gap-3 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-xs text-slate-500">Amount</dt>
                <dd>{formatMoney(detail.amount, detail.currency)}</dd>
              </div>
              <div>
                <dt className="text-xs text-slate-500">Status</dt>
                <dd className="mt-1">
                  <PaymentStatusBadge status={detail.status} />
                </dd>
              </div>
              <div>
                <dt className="text-xs text-slate-500">UETR</dt>
                <dd className="font-mono text-xs">{detail.uetr ?? "—"}</dd>
              </div>
              <div>
                <dt className="text-xs text-slate-500">Route</dt>
                <dd>
                  {codes.get(detail.senderParticipantId) ?? "?"} →{" "}
                  {detail.destinationParticipantId
                    ? (codes.get(detail.destinationParticipantId) ?? "?")
                    : "—"}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-slate-500">Accounts</dt>
                <dd className="font-mono text-xs">
                  {detail.sourceAccount} → {detail.destinationAccount}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-slate-500">External ref</dt>
                <dd className="font-mono text-xs">{detail.externalReference ?? "—"}</dd>
              </div>
            </dl>
            {detail.status === "ACCEPTED" || detail.status === "PROCESSING" ? (
              <p className="text-xs text-slate-500">Status refreshes automatically while the payment is in flight.</p>
            ) : null}
            {detail.failureReason ? (
              <p className="rounded-md bg-rose-50 px-3 py-2 text-sm text-rose-800">{detail.failureReason}</p>
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
