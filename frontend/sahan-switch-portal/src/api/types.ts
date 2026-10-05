export type Role = "ADMIN" | "PARTICIPANT";

export type ParticipantType = "BANK" | "MOBILE_WALLET" | "GOVERNMENT" | "OTHER";

export type ParticipantStatus = "ACTIVE" | "INACTIVE";

export type PaymentStatus = "ACCEPTED" | "PROCESSING" | "COMPLETED" | "FAILED" | "PENDING";

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  username: string;
  role: Role;
  participantId?: string | null;
}

export interface Session {
  token: string;
  username: string;
  role: Role;
  participantId: string | null;
}

export interface ApiErrorBody {
  timestamp?: string;
  status: number;
  error: string;
  message: string;
  fieldErrors?: Record<string, string> | null;
}

export class ApiError extends Error {
  readonly status: number;
  readonly error: string;
  readonly fieldErrors: Record<string, string>;

  constructor(body: ApiErrorBody) {
    super(body.message || body.error || "Request failed");
    this.name = "ApiError";
    this.status = body.status;
    this.error = body.error;
    this.fieldErrors = body.fieldErrors ?? {};
  }
}

export interface Participant {
  id: string;
  code: string;
  name: string;
  type: ParticipantType;
  status: ParticipantStatus;
  createdAt: string;
  updatedAt: string;
  hasApiKey: boolean;
  apiKey?: string | null;
}

export interface Payment {
  id: string;
  paymentReference: string;
  senderParticipantId: string;
  destinationParticipantId: string | null;
  sourceAccount: string;
  destinationAccount: string;
  amount: number | string;
  currency: string;
  status: PaymentStatus;
  externalReference: string | null;
  failureReason: string | null;
  endToEndId: string | null;
  uetr: string | null;
  debtorName: string | null;
  creditorName: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AuditEntry {
  id: string;
  paymentId: string;
  previousStatus: PaymentStatus | null;
  newStatus: PaymentStatus;
  reason: string;
  correlationId: string | null;
  createdAt: string;
}

export interface SpringPage<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface PaymentSearchParams {
  page?: number;
  size?: number;
  status?: PaymentStatus | "";
  senderParticipantId?: string;
  destinationParticipantId?: string;
  currency?: string;
  reference?: string;
  fromDate?: string;
  toDate?: string;
  sort?: string;
}

export interface AnalyticsSummary {
  from: string;
  to: string;
  windowHours: number;
  totals: {
    transactionCount: number;
    completed: number;
    failed: number;
    inFlight: number;
    successRatePercent: number | null;
    settledValue: { currency: string; value: number | string }[];
  };
  byCurrencyAndStatus: {
    currency: string;
    status: PaymentStatus;
    count: number;
    totalAmount: number | string;
  }[];
  participants: {
    participantId: string;
    code: string;
    name: string;
    type: ParticipantType;
    total: number;
    completed: number;
    failed: number;
    inFlight: number;
    successRatePercent: number | null;
    failureRatePercent: number | null;
  }[];
  hourly: {
    hour: string;
    total: number;
    completed: number;
    failed: number;
    processing: number;
  }[];
  activeParticipants: number;
  circuitBreakers: {
    participantId: string;
    participantCode: string;
    participantName: string;
    state: string;
    failureRatePercent: number | null;
    bufferedCalls: number;
    notPermittedCalls: number;
  }[];
}
