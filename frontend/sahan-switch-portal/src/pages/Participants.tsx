import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Copy } from "lucide-react";
import { api } from "../api/client";
import type { Participant, ParticipantType } from "../api/types";
import { DataTable } from "../components/DataTable";
import { Modal } from "../components/Modal";
import { ParticipantStatusBadge, TypeBadge } from "../components/StatusBadge";

const TYPES: ParticipantType[] = ["BANK", "MOBILE_WALLET", "GOVERNMENT", "OTHER"];

export function ParticipantsPage() {
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ["participants"], queryFn: api.participants });
  const [registerOpen, setRegisterOpen] = useState(false);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [type, setType] = useState<ParticipantType>("BANK");
  const [formError, setFormError] = useState<string | null>(null);
  const [issuedKey, setIssuedKey] = useState<{ code: string; apiKey: string } | null>(null);
  const [copied, setCopied] = useState(false);

  const create = useMutation({
    mutationFn: () => api.createParticipant({ code: code.trim(), name: name.trim(), type }),
    onSuccess: (participant) => {
      void queryClient.invalidateQueries({ queryKey: ["participants"] });
      setRegisterOpen(false);
      setCode("");
      setName("");
      if (participant.apiKey) {
        setIssuedKey({ code: participant.code, apiKey: participant.apiKey });
      }
    },
    onError: (error) => {
      setFormError(error instanceof Error ? error.message : "Could not register participant");
    },
  });

  const deactivate = useMutation({
    mutationFn: (id: string) => api.deactivateParticipant(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["participants"] }),
  });

  const rotate = useMutation({
    mutationFn: (id: string) => api.issueApiKey(id),
    onSuccess: (participant) => {
      void queryClient.invalidateQueries({ queryKey: ["participants"] });
      if (participant.apiKey) {
        setIssuedKey({ code: participant.code, apiKey: participant.apiKey });
      }
    },
  });

  function onRegister(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    create.mutate();
  }

  async function copyKey() {
    if (!issuedKey) {
      return;
    }
    await navigator.clipboard.writeText(issuedKey.apiKey);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1500);
  }

  const rows = query.data ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Participants</h1>
          <p className="text-sm text-slate-500">Register institutions, deactivate them, and issue API keys.</p>
        </div>
        <button
          type="button"
          className="rounded-md bg-teal-700 px-3 py-2 text-sm text-white hover:bg-teal-600"
          onClick={() => setRegisterOpen(true)}
        >
          Register
        </button>
      </div>

      {query.isError ? (
        <p className="text-sm text-rose-700">{query.error instanceof Error ? query.error.message : "Failed to load"}</p>
      ) : null}

      <DataTable
        columns={[
          { key: "code", header: "Code", render: (row) => <span className="font-mono font-medium">{row.code}</span> },
          { key: "name", header: "Name", render: (row) => row.name },
          { key: "type", header: "Type", render: (row) => <TypeBadge type={row.type} /> },
          { key: "status", header: "Status", render: (row) => <ParticipantStatusBadge status={row.status} /> },
          {
            key: "key",
            header: "API key",
            render: (row) => (row.hasApiKey ? "Issued" : "None"),
          },
          {
            key: "actions",
            header: "",
            className: "text-right",
            render: (row) => (
              <div className="flex justify-end gap-2" onClick={(event) => event.stopPropagation()}>
                <button
                  type="button"
                  className="rounded border border-slate-200 px-2 py-1 text-xs hover:bg-slate-50"
                  onClick={() => {
                    if (window.confirm(`Issue a new API key for ${row.code}? The previous key stops working immediately.`)) {
                      rotate.mutate(row.id);
                    }
                  }}
                >
                  {row.hasApiKey ? "Rotate key" : "Issue key"}
                </button>
                {row.status === "ACTIVE" ? (
                  <button
                    type="button"
                    className="rounded border border-rose-200 px-2 py-1 text-xs text-rose-700 hover:bg-rose-50"
                    onClick={() => {
                      if (window.confirm(`Deactivate ${row.code}? It will no longer be able to send or receive payments.`)) {
                        deactivate.mutate(row.id);
                      }
                    }}
                  >
                    Deactivate
                  </button>
                ) : null}
              </div>
            ),
          },
        ]}
        rows={rows}
        rowKey={(row: Participant) => row.id}
        empty={query.isLoading ? "Loading…" : "No participants yet."}
        page={0}
        totalPages={1}
        totalElements={rows.length}
        onPageChange={() => undefined}
      />

      <Modal title="Register participant" open={registerOpen} onClose={() => setRegisterOpen(false)}>
        <form className="space-y-3" onSubmit={onRegister}>
          <label className="block text-xs font-medium text-slate-500">
            Code
            <input
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm uppercase"
              value={code}
              onChange={(event) => setCode(event.target.value)}
              required
              maxLength={50}
            />
          </label>
          <label className="block text-xs font-medium text-slate-500">
            Name
            <input
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
              maxLength={150}
            />
          </label>
          <label className="block text-xs font-medium text-slate-500">
            Type
            <select
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              value={type}
              onChange={(event) => setType(event.target.value as ParticipantType)}
            >
              {TYPES.map((item) => (
                <option key={item} value={item}>
                  {item.replaceAll("_", " ")}
                </option>
              ))}
            </select>
          </label>
          {formError ? <p className="text-sm text-rose-700">{formError}</p> : null}
          <div className="flex justify-end gap-2 pt-2">
            <button type="button" className="rounded-md px-3 py-2 text-sm" onClick={() => setRegisterOpen(false)}>
              Cancel
            </button>
            <button
              type="submit"
              disabled={create.isPending}
              className="rounded-md bg-teal-700 px-3 py-2 text-sm text-white disabled:opacity-60"
            >
              {create.isPending ? "Saving…" : "Create"}
            </button>
          </div>
        </form>
      </Modal>

      <Modal
        title={issuedKey ? `API key for ${issuedKey.code}` : "API key"}
        open={issuedKey != null}
        onClose={() => setIssuedKey(null)}
      >
        {issuedKey ? (
          <div className="space-y-3">
            <p className="text-sm text-slate-600">
              Copy this key now. It is shown once; afterwards only a hash is stored.
            </p>
            <code className="block break-all rounded-md bg-slate-950 p-3 text-xs text-emerald-100">{issuedKey.apiKey}</code>
            <button
              type="button"
              onClick={copyKey}
              className="inline-flex items-center gap-1 rounded-md bg-teal-700 px-3 py-2 text-sm text-white"
            >
              {copied ? <Check size={14} /> : <Copy size={14} />}
              {copied ? "Copied" : "Copy key"}
            </button>
          </div>
        ) : null}
      </Modal>
    </div>
  );
}
