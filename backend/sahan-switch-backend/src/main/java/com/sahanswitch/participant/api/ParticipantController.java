package com.sahanswitch.participant.api;

import com.sahanswitch.participant.application.ParticipantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/participants")
public class ParticipantController {

    private final ParticipantService participantService;

    public ParticipantController(
            ParticipantService participantService
    ) {
        this.participantService = participantService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipantResponse create(@Valid @RequestBody CreateParticipantRequest request) {
        return participantService.create(request);
    }

    @GetMapping("/{id}")
    public ParticipantResponse getById(@PathVariable UUID id) {
        return participantService.getById(id);
    }

    @GetMapping
    public List<ParticipantResponse> findAll() {
        return participantService.findAll();
    }

    /** Task 1: issue or rotate the participant's API key. The clear text appears only here. */
    @PostMapping("/{id}/api-key")
    public ParticipantResponse issueApiKey(@PathVariable UUID id) {
        return participantService.issueApiKey(id);
    }

    @PatchMapping("/{id}/deactivate")
    public ParticipantResponse deactivate(@PathVariable UUID id) {
        return participantService.deactivate(id);
    }
}