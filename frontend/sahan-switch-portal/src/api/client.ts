import {
  ApiError,
  type AnalyticsSummary,
  type ApiErrorBody,
  type AuditEntry,
  type LoginResponse,
  type Participant,
  type ParticipantType,
  type Payment,
  type PaymentSearchParams,
  type SpringPage,
} from "./types";

const TOKEN_KEY = "sahan.session";

type UnauthorizedHandler = () => void;

let onUnauthorized: UnauthorizedHandler | null = null;

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null) {
  onUnauthorized = handler;
}

export function readStoredToken(): string | null {
  try {
    const raw = sessionStorage.getItem(TOKEN_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as { token?: string };
    return parsed.token ?? null;
  } catch {
    return null;
  }
}

export function persistSession(session: unknown) {
  sessionStorage.setItem(TOKEN_KEY, JSON.stringify(session));
}

export function clearSession() {
  sessionStorage.removeItem(TOKEN_KEY);
}

export function loadSession<T>(): T | null {
  try {
    const raw = sessionStorage.getItem(TOKEN_KEY);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

async function request<T>(path: string, init: RequestInit = {}, accept = "application/json"): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", accept);
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const token = readStoredToken();
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(path, { ...init, headers });

  if (response.status === 401) {
    onUnauthorized?.();
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  if (accept.includes("xml") || accept.includes("text")) {
    return (await response.text()) as T;
  }

  return (await response.json()) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  const fallback: ApiErrorBody = {
    status: response.status,
    error: response.statusText || "Error",
    message: `Request failed (${response.status})`,
  };
  try {
    const body = (await response.json()) as ApiErrorBody;
    return new ApiError({ ...fallback, ...body, status: body.status ?? response.status });
  } catch {
    return new ApiError(fallback);
  }
}

export const api = {
  login(username: string, password: string) {
    return request<LoginResponse>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
  },

  analytics(hours = 24) {
    return request<AnalyticsSummary>(`/api/v1/analytics/summary?hours=${hours}`);
  },

  participants() {
    return request<Participant[]>("/api/v1/participants");
  },

  createParticipant(input: { code: string; name: string; type: ParticipantType }) {
    return request<Participant>("/api/v1/participants", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },

  deactivateParticipant(id: string) {
    return request<Participant>(`/api/v1/participants/${id}/deactivate`, { method: "PATCH" });
  },

  issueApiKey(id: string) {
    return request<Participant>(`/api/v1/participants/${id}/api-key`, { method: "POST" });
  },

  searchPayments(params: PaymentSearchParams) {
    const query = new URLSearchParams();
    query.set("page", String(params.page ?? 0));
    query.set("size", String(params.size ?? 20));
    query.set("sort", params.sort ?? "createdAt,desc");
    if (params.status) query.set("status", params.status);
    if (params.senderParticipantId) query.set("senderParticipantId", params.senderParticipantId);
    if (params.destinationParticipantId) {
      query.set("destinationParticipantId", params.destinationParticipantId);
    }
    if (params.currency) query.set("currency", params.currency);
    if (params.reference) query.set("reference", params.reference);
    if (params.fromDate) query.set("fromDate", params.fromDate);
    if (params.toDate) query.set("toDate", params.toDate);
    return request<SpringPage<Payment>>(`/api/v1/payments/search?${query.toString()}`);
  },

  payment(id: string) {
    return request<Payment>(`/api/v1/payments/${id}`);
  },

  audit(id: string) {
    return request<AuditEntry[]>(`/api/v1/payments/${id}/audit`);
  },

  pacs008(id: string) {
    return request<string>(`/api/v1/payments/${id}/pacs008`, {}, "application/xml");
  },

  pacs002(id: string) {
    return request<string>(`/api/v1/payments/${id}/pacs002`, {}, "application/xml");
  },
};
