import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  AlertTriangle,
  Building2,
  CheckCircle2,
  CircleDollarSign,
} from "lucide-react";
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  BarChart,
  Bar,
} from "recharts";
import { api } from "../api/client";
import { StatCard } from "../components/StatCard";
import { formatCount, formatMoney, formatPercent } from "../lib/format";
import { CircuitBreakerBadge } from "../components/StatusBadge";

export function DashboardPage() {
  const query = useQuery({
    queryKey: ["analytics", 24],
    queryFn: () => api.analytics(24),
    refetchInterval: 5_000,
  });

  if (query.isLoading) {
    return <p className="text-sm text-slate-500">Loading dashboard…</p>;
  }

  if (query.isError) {
    return (
      <p className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
        {query.error instanceof Error ? query.error.message : "Failed to load analytics"}
      </p>
    );
  }

  const data = query.data;
  if (!data) {
    return null;
  }

  const primaryValue = data.totals.settledValue[0];
  const hourly = data.hourly.map((bucket) => ({
    ...bucket,
    label: new Date(bucket.hour).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
  }));
  const statusChart = [
    { name: "Completed", value: data.totals.completed },
    { name: "Failed", value: data.totals.failed },
    { name: "In flight", value: data.totals.inFlight },
  ];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold text-slate-900">Dashboard</h1>
        <p className="text-sm text-slate-500">Last {data.windowHours} hours · refreshes every 5 seconds</p>
      </div>

      <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Volume 24h"
          value={formatCount(data.totals.transactionCount)}
          hint={`${formatCount(data.totals.inFlight)} still in flight`}
          icon={Activity}
        />
        <StatCard
          label="Settled value"
          value={primaryValue ? formatMoney(primaryValue.value, primaryValue.currency) : "—"}
          hint={data.totals.settledValue.length > 1 ? "Primary currency shown" : "Completed payments only"}
          icon={CircleDollarSign}
        />
        <StatCard
          label="Success rate"
          value={formatPercent(data.totals.successRatePercent)}
          hint={`${formatCount(data.totals.completed)} completed · ${formatCount(data.totals.failed)} failed`}
          icon={CheckCircle2}
        />
        <StatCard
          label="Active participants"
          value={formatCount(data.activeParticipants)}
          icon={Building2}
        />
      </section>

      <section className="grid gap-4 lg:grid-cols-3">
        <article className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm lg:col-span-2">
          <h2 className="text-sm font-semibold text-slate-800">Hourly trend</h2>
          <div className="mt-3 h-72">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={hourly}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis dataKey="label" tick={{ fontSize: 11 }} />
                <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
                <Tooltip />
                <Legend />
                <Line type="monotone" dataKey="total" stroke="#0f766e" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="completed" stroke="#059669" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="failed" stroke="#e11d48" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="processing" stroke="#d97706" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </article>

        <article className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-800">Status breakdown</h2>
          <div className="mt-3 h-72">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={statusChart}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis dataKey="name" tick={{ fontSize: 11 }} />
                <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
                <Tooltip />
                <Bar dataKey="value" fill="#0f766e" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </article>
      </section>

      <article className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex items-center gap-2">
          <AlertTriangle size={16} className="text-amber-600" />
          <h2 className="text-sm font-semibold text-slate-800">Circuit breakers</h2>
        </div>
        <div className="mt-3 overflow-x-auto">
          <table className="min-w-full text-left text-sm">
            <thead className="text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-2 py-2">Participant</th>
                <th className="px-2 py-2">State</th>
                <th className="px-2 py-2">Failure rate</th>
                <th className="px-2 py-2">Blocked calls</th>
              </tr>
            </thead>
            <tbody>
              {data.circuitBreakers.map((breaker) => (
                <tr key={breaker.participantId} className="border-t border-slate-100">
                  <td className="px-2 py-2 font-medium">
                    {breaker.participantCode}
                    <span className="ml-2 text-xs text-slate-500">{breaker.participantName}</span>
                  </td>
                  <td className="px-2 py-2">
                    <CircuitBreakerBadge state={breaker.state} />
                  </td>
                  <td className="px-2 py-2">{formatPercent(breaker.failureRatePercent)}</td>
                  <td className="px-2 py-2">{formatCount(breaker.notPermittedCalls)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </article>
    </div>
  );
}
