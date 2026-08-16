package com.sahanswitch.participant.application;

import com.sahanswitch.common.exception.DuplicateResourceException;
import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.participant.api.CreateParticipantRequest;
import com.sahanswitch.participant.api.ParticipantResponse;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.domain.ParticipantStatus;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
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

    public ParticipantService(
            ParticipantRepository participantRepository
    ) {
        this.participantRepository = participantRepository;
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
//        Save to DB
        Participant savedParticipant =
                participantRepository.save(participant);
// Retrun Reponse
        return toResponse(savedParticipant);
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
        return new ParticipantResponse(
                participant.getId(),
                participant.getCode(),
                participant.getName(),
                participant.getType(),
                participant.getStatus(),
                participant.getCreatedAt(),
                participant.getUpdatedAt()
        );
    }
}