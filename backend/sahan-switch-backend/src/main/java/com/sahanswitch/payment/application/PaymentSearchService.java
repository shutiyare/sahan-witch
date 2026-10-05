package com.sahanswitch.payment.application;

import com.sahanswitch.payment.api.PaymentResponse;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import com.sahanswitch.payment.infrastructure.PaymentSpecifications;
import com.sahanswitch.security.CurrentIdentity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task 3: paginated, filterable payment search.
 *
 * <p>Safety rules applied here, not trusted from the caller:
 * <ul>
 *   <li><b>Visibility</b>: a participant identity only ever sees payments it sent or received;
 *       the filter is added on top of whatever the caller asked for. Administrators see all.</li>
 *   <li><b>Sort whitelist</b>: sorting by an unknown property would surface as a 500 from
 *       Hibernate; unknown names are rejected with a clear 400 instead.</li>
 *   <li><b>Page size cap</b> ({@value #MAX_PAGE_SIZE}) so one request cannot load the table.</li>
 * </ul>
 */
@Service
public class PaymentSearchService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final int DEFAULT_PAGE_SIZE = 20;

    /** Properties of Payment a client may sort by. */
    static final Set<String> SORTABLE_PROPERTIES =
            Set.of("createdAt", "updatedAt", "amount", "currency", "status", "paymentReference");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final PaymentRepository paymentRepository;
    private final CurrentIdentity currentIdentity;

    public PaymentSearchService(PaymentRepository paymentRepository, CurrentIdentity currentIdentity) {
        this.paymentRepository = paymentRepository;
        this.currentIdentity = currentIdentity;
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> search(PaymentSearchCriteria criteria, Pageable requested) {

        CurrentIdentity.Identity identity = currentIdentity.require();

        UUID participantScope = identity.admin() ? null : identity.participantId();

        Pageable pageable = sanitize(requested);

        return paymentRepository
                .findAll(PaymentSpecifications.from(criteria, participantScope), pageable)
                .map(PaymentResponse::from);
    }

    /** Applies the page-size cap and the sort whitelist; supplies defaults. */
    static Pageable sanitize(Pageable requested) {

        int size = requested.isPaged() ? requested.getPageSize() : DEFAULT_PAGE_SIZE;
        int page = requested.isPaged() ? requested.getPageNumber() : 0;

        if (size < 1) {
            size = DEFAULT_PAGE_SIZE;
        }
        size = Math.min(size, MAX_PAGE_SIZE);

        Sort sort = requested.getSort();

        if (sort.isUnsorted()) {
            sort = DEFAULT_SORT;
        } else {
            List<String> rejected = sort.stream()
                    .map(Sort.Order::getProperty)
                    .filter(property -> !SORTABLE_PROPERTIES.contains(property))
                    .toList();

            if (!rejected.isEmpty()) {
                throw new IllegalArgumentException(
                        "Cannot sort by " + rejected + ". Allowed: " + SORTABLE_PROPERTIES.stream().sorted().toList()
                );
            }
        }

        return PageRequest.of(page, size, sort);
    }
}
