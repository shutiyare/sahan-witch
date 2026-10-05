package com.sahanswitch.payment.application;

/** Task 2: when a payment is sent to the destination participant. */
public enum RoutingMode {

    /**
     * Inside the request: the caller gets the final status (COMPLETED / FAILED) in the response.
     * Simple, but the request thread and a database connection wait for the participant.
     */
    SYNC,

    /**
     * After the request, through the outbox and RabbitMQ: the caller gets status ACCEPTED at once
     * and the final status arrives moments later (poll the payment, or its pacs.002).
     */
    ASYNC
}
