package com.sahanswitch.common.exception;

import com.sahanswitch.iso20022.domain.Iso20022ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 6 / Task 1.3: the HTTP status and body produced for each kind of exception.
 * Runs {@link GlobalExceptionHandler} in a standalone MockMvc (no Spring context, no database)
 * with a tiny controller that simply throws.
 */
class GlobalExceptionHandlerTest {

    record Body(@NotBlank(message = "name is required") String name, @NotNull(message = "age is required") Integer age) {
    }

    @RestController
    static class ThrowingController {

        @PostMapping("/validate")
        String validate(@Valid @RequestBody Body body) {
            return "ok";
        }

        @GetMapping("/header")
        String header(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }

        @GetMapping("/throw/{type}")
        String throwing(@PathVariable String type) {
            switch (type) {
                case "optimistic" -> throw new ObjectOptimisticLockingFailureException("Payment", "id-1");
                case "illegal-state" -> throw new IllegalStateException("Sender participant is inactive");
                case "illegal-argument" -> throw new IllegalArgumentException("Sender and destination must differ");
                case "idempotency" -> throw new IdempotencyConflictException("key reused");
                case "payment-state" -> throw new InvalidPaymentStateException("Only PROCESSING payments can be completed");
                case "not-found" -> throw new ResourceNotFoundException("Payment not found: 1");
                case "duplicate" -> throw new DuplicateResourceException("already exists");
                case "integrity" -> throw new DataIntegrityViolationException("uk_something");
                case "iso" -> throw new Iso20022ValidationException("pacs.008 invalid",
                        Map.of("PmtId/UETR", "UETR is required"));
                case "iso-no-details" -> throw new Iso20022ValidationException("XML is not well-formed");
                default -> throw new IllegalStateException("unknown test case");
            }
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ================================================================ content negotiation / unreadable body

    /**
     * Regression: an ISO 20022 client sends "Accept: application/xml". The error body is JSON, so
     * before the handler pinned its content type this produced HttpMediaTypeNotAcceptableException
     * and the real error became a 500.
     */
    @Test
    void errorsAreJsonEvenWhenClientAcceptsOnlyXml() throws Exception {
        mockMvc.perform(get("/throw/not-found").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/throw/iso").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['PmtId/UETR']").value("UETR is required"));
    }

    @Test
    void malformedJsonBodyReturns400WithApiErrorShape() throws Exception {
        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Malformed Request"));
    }

    // ================================================================ validation -> fieldErrors

    @Test
    void validationFailureReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors.name").value("name is required"))
                .andExpect(jsonPath("$.fieldErrors.age").value("age is required"));
    }

    @Test
    void validationFailureListsOnlyTheInvalidFields() throws Exception {
        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bob\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.age").value("age is required"))
                .andExpect(jsonPath("$.fieldErrors.name").doesNotExist());
    }

    @Test
    void errorsWithoutFieldDetailsOmitTheFieldErrorsProperty() throws Exception {
        mockMvc.perform(get("/throw/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    // ================================================================ 409

    @Test
    void optimisticLockFailureReturns409AskingForRetry() throws Exception {
        mockMvc.perform(get("/throw/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Concurrent Modification"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("retry")));
    }

    @Test
    void conflictsReturn409() throws Exception {
        mockMvc.perform(get("/throw/idempotency")).andExpect(status().isConflict());
        mockMvc.perform(get("/throw/payment-state")).andExpect(status().isConflict());
        mockMvc.perform(get("/throw/duplicate")).andExpect(status().isConflict());
        mockMvc.perform(get("/throw/integrity")).andExpect(status().isConflict());
    }

    // ================================================================ 404 / 400 / 422

    @Test
    void notFoundReturns404() throws Exception {
        mockMvc.perform(get("/throw/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment not found: 1"));
    }

    @Test
    void illegalArgumentReturns400() throws Exception {
        mockMvc.perform(get("/throw/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Sender and destination must differ"));
    }

    @Test
    void illegalStateReturns422() throws Exception {
        mockMvc.perform(get("/throw/illegal-state"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Sender participant is inactive"));
    }

    @Test
    void missingRequiredHeaderReturns400() throws Exception {
        mockMvc.perform(get("/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
    }

    // ================================================================ ISO 20022

    @Test
    void isoValidationReturns400WithViolationsAsFieldErrors() throws Exception {
        mockMvc.perform(get("/throw/iso"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid ISO 20022 Message"))
                .andExpect(jsonPath("$.fieldErrors['PmtId/UETR']").value("UETR is required"));
    }

    @Test
    void isoValidationWithoutDetailsHasNoFieldErrors() throws Exception {
        mockMvc.perform(get("/throw/iso-no-details"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("XML is not well-formed"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }
}
