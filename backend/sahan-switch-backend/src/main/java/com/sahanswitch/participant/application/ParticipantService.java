package com.sahanswitch.participant.application;

import com.sahanswitch.common.exception.DuplicateResourceException;
import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.participant.api.CreateParticipantRequest;
import com.sahanswitch.participant.api.ParticipantResponse;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.domain.ParticipantStatus;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.security.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ParticipantService {
    Logger logger = LoggerFactory.getLogger(ParticipantService.class);
    private final ParticipantRepository participantRepository;
    private final ApiKeyService apiKeyService;

    public ParticipantService(
            ParticipantRepository participantRepository,
            ApiKeyService apiKeyService
    ) {
        this.participantRepository = participantRepository;
        this.apiKeyService = apiKeyService;
    }

    @Transactional
    public ParticipantResponse create (CreateParticipantRequest request) {
//        Normalize Code
        String normalizedCode = request.code().trim().toUpperCase();
//      Check if Alread Exists By Code
        if (participantRepository.existsByCode(normalizedCode)) {
            throw new DuplicateResourceException(
                    "Participant with code '" + normalizedCode
                            + "' already exists"
            );
        }
//        Create New Instance
        Participant participant = new Participant(
                normalizedCode,
                request.name().trim(),
                request.type(),
                ParticipantStatus.ACTIVE
        );
//        Task 1: every new participant gets an API key. Only its hash is stored; the clear text
//        is returned in this one response and can never be retrieved again.
        String apiKey = apiKeyService.generateKey();
        participant.assignApiKeyHash(apiKeyService.hash(apiKey));
//        Save to DB
        Participant savedParticipant =
                participantRepository.save(participant);
// Retrun Reponse
        return toResponse(savedParticipant, apiKey);
    }

    /**
     * Task 1: issues a new API key for an existing participant (or replaces the current one).
     * The previous key stops working immediately. The clear-text key is only in this response.
     */
    @Transactional
    public ParticipantResponse issueApiKey(UUID id) {

        Participant participant = participantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Participant with id '" + id + "' was not found"
                ));

        String apiKey = apiKeyService.generateKey();
        participant.assignApiKeyHash(apiKeyService.hash(apiKey));

        logger.info("API key issued for participant {}", participant.getCode());

        return toResponse(participant, apiKey);
    }

    public ParticipantResponse getById(UUID id) {
        Participant participant =
                participantRepository.findById(id)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Participant with id '" + id
                                                + "' was not found"
                                )
                        );

        return toResponse(participant);
    }

    public List<ParticipantResponse> findAll() {
        return participantRepository
                .findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ParticipantResponse deactivate(UUID id) {

        Participant participant =
                participantRepository.findById(id)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Participant with id '" + id
                                                + "' was not found"
                                )
                        );

        participant.deactivate();

        return toResponse(participant);
    }

    private ParticipantResponse toResponse(Participant participant) {
        return toResponse(participant, null);
    }

    private ParticipantResponse toResponse(Participant participant, String clearTextApiKey) {
        return new ParticipantResponse(
                participant.getId(),
                participant.getCode(),
                participant.getName(),
                participant.getType(),
                participant.getStatus(),
                participant.getCreatedAt(),
                participant.getUpdatedAt(),
                participant.hasApiKey(),
                clearTextApiKey
        );
    }
}