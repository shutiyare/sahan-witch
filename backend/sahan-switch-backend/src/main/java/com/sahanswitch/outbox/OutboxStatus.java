package com.sahanswitch.outbox;

/** Life cycle of an outbox row (stored as text in {@code outbox_events.status}). */
public enum OutboxStatus {

    /** Written, waiting for the relay to publish it. */
    PENDING,

    /** The broker confirmed receipt. */
    PUBLISHED,

    /** Gave up after too many failed attempts; needs an operator (reset to PENDING to retry). */
    FAILED
}
