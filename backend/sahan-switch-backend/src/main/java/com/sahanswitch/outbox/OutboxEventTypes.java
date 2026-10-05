package com.sahanswitch.outbox;

/** Task 2: names of the events that travel through the outbox. */
public final class OutboxEventTypes {

    public static final String AGGREGATE_PAYMENT = "PAYMENT";

    /** "Please route this ACCEPTED payment to its destination participant." */
    public static final String PAYMENT_ROUTING_REQUESTED = "PaymentRoutingRequested";

    /** "A payment changed status" (one per transition; also what the audit trail records). */
    public static final String PAYMENT_STATUS_CHANGED = "PaymentStatusChanged";

    private OutboxEventTypes() {
    }
}
