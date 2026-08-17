package com.sahanswitch.integration.domain;

public record IntegrationResult<IntegrationStatus>(

        IntegrationStatus status,

        String externalReference,

        String message

) {
}