package com.sahanswitch.security;

/** Task 1: who is calling. Stored as text in {@code users.role}. */
public enum Role {

    /** The switch operator: sees every payment and manages participants. */
    ADMIN,

    /** An institution (or one of its users): limited to its own payments. */
    PARTICIPANT
}
