package com.sahanswitch.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Task 2: the only way business code puts a message into the outbox.
 *
 * <p>{@link #append} must run inside the transaction of the change it describes. It refuses to
 * run outside one, because an outbox row written on its own would defeat the whole pattern.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param payload any object that Jackson can serialize (usually a record or a Map)
     * @return the stored event
     */
    public OutboxEvent append(String aggregateType, UUID aggregateId, String type, Object payload) {

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Outbox events must be written inside the transaction of the change they describe"
            );
        }

        String json = objectMapper.writeValueAsString(payload);

        return repository.save(new OutboxEvent(aggregateType, aggregateId, type, json));
    }
}
