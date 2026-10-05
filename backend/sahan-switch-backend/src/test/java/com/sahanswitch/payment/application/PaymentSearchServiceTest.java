package com.sahanswitch.payment.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentSearchServiceTest {

    @Test
    void unpagedRequestsGetDefaultsNewestFirst() {
        Pageable result = PaymentSearchService.sanitize(Pageable.unpaged());

        assertEquals(0, result.getPageNumber());
        assertEquals(20, result.getPageSize());
        assertEquals(Sort.Direction.DESC, result.getSort().getOrderFor("createdAt").getDirection());
    }

    @Test
    void pageSizeIsCappedAtTheMaximum() {
        Pageable result = PaymentSearchService.sanitize(PageRequest.of(3, 100_000));

        assertEquals(PaymentSearchService.MAX_PAGE_SIZE, result.getPageSize());
        assertEquals(3, result.getPageNumber(), "the page number is kept");
    }

    @Test
    void allowedSortPropertiesPass() {
        for (String property : PaymentSearchService.SORTABLE_PROPERTIES) {
            Pageable result = PaymentSearchService.sanitize(PageRequest.of(0, 10, Sort.by(property).ascending()));
            assertTrue(result.getSort().getOrderFor(property) != null, property);
        }
    }

    @Test
    void unknownSortPropertiesAreRejectedWithTheAllowedList() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PaymentSearchService.sanitize(PageRequest.of(0, 10, Sort.by("senderParticipant.apiKeyHash"))));

        assertTrue(exception.getMessage().contains("senderParticipant.apiKeyHash"));
        assertTrue(exception.getMessage().contains("createdAt"), "the message lists what is allowed");
    }
}
