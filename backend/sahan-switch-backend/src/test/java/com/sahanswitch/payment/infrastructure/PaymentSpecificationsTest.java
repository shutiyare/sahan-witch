package com.sahanswitch.payment.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentSpecificationsTest {

    @Test
    void likeWildcardsAreEscapedSoUserInputIsAlwaysLiteral() {
        assertEquals("PAY-123", PaymentSpecifications.escapeLike("PAY-123"));
        assertEquals("100\\%", PaymentSpecifications.escapeLike("100%"));
        assertEquals("A\\_B", PaymentSpecifications.escapeLike("A_B"));
        assertEquals("\\\\", PaymentSpecifications.escapeLike("\\"));
        assertEquals("\\\\\\%", PaymentSpecifications.escapeLike("\\%"), "the escape character itself is escaped first");
    }
}
