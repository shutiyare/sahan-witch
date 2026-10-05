package com.sahanswitch.iso20022.application;

import com.sahanswitch.iso20022.domain.Iso20022ValidationException;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.CreditTransferTransaction;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.FIToFICustomerCreditTransfer;
import com.sahanswitch.iso20022.infrastructure.Iso20022XmlSupport;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.payment.api.InitiatePaymentRequest;
import jakarta.xml.bind.JAXBContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parses and validates an incoming pacs.008 message and turns it into the switch's own
 * {@link InitiatePaymentRequest}.
 *
 * <p><b>Task 4.2.</b> The flow is: secure XML parsing -> check every mandatory element ->
 * resolve the sender / destination participants from their codes -> build the request.
 * <b>All</b> problems are collected and reported together
 * ({@link Iso20022ValidationException#getViolations()}), so a sender can fix everything in
 * one round trip.
 *
 * <p>Mandatory data checked (the spec's list): EndToEndId, UETR, settlement amount (and
 * currency), Debtor, Creditor - plus what the switch needs to route: both agents (participant
 * codes) and both accounts.
 *
 * <p>Limitation: one transaction per message ({@code NbOfTxs = 1}). Batch messages are
 * rejected rather than partially processed.
 */
@Service
public class Iso20022MessageTransformer {

    /** UUID v4 in the textual form required for UETR (case-insensitive here, normalised later). */
    private static final Pattern UETR_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    /** The payments.amount column is NUMERIC(19,4). */
    private static final int MAX_AMOUNT_SCALE = 4;

    private static final int MAX_END_TO_END_ID = 35;
    private static final int MAX_NAME = 140;
    private static final int MAX_ACCOUNT = 100;

    private final ParticipantRepository participantRepository;

    private final JAXBContext context = Iso20022XmlSupport.newContext(Pacs008Document.class);

    public Iso20022MessageTransformer(ParticipantRepository participantRepository) {
        this.participantRepository = participantRepository;
    }

    /**
     * Parses a pacs.008 XML string.
     *
     * @throws Iso20022ValidationException if the XML is malformed or any mandatory data is
     *                                     missing or invalid
     */
    @Transactional(readOnly = true)
    public InitiatePaymentRequest parsePacs008(String xml) {

        Pacs008Document document = Iso20022XmlSupport.unmarshal(context, xml, Pacs008Document.class);

        // Violations are keyed by the ISO element path so the sender knows exactly where to look.
        Map<String, String> violations = new LinkedHashMap<>();

        CreditTransferTransaction transaction = findSingleTransaction(document, violations);

        if (transaction == null) {
            throw failure(violations);
        }

        // --- payment identification: EndToEndId + UETR -------------------------------------
        String endToEndId = null;
        UUID uetr = null;

        if (transaction.paymentIdentification == null) {
            violations.put("PmtId", "Payment identification is required");
        } else {
            endToEndId = trimToNull(transaction.paymentIdentification.endToEndId);
            if (endToEndId == null) {
                violations.put("PmtId/EndToEndId", "EndToEndId is required");
            } else if (endToEndId.length() > MAX_END_TO_END_ID) {
                violations.put("PmtId/EndToEndId", "EndToEndId must not exceed 35 characters");
            }

            String rawUetr = trimToNull(transaction.paymentIdentification.uetr);
            if (rawUetr == null) {
                violations.put("PmtId/UETR", "UETR is required");
            } else if (!UETR_PATTERN.matcher(rawUetr).matches()) {
                violations.put("PmtId/UETR", "UETR must be a UUID version 4");
            } else {
                uetr = UUID.fromString(rawUetr.toLowerCase(Locale.ROOT));
            }
        }

        // --- settlement amount ------------------------------------------------------------
        BigDecimal amount = null;
        String currency = null;

        if (transaction.interbankSettlementAmount == null) {
            violations.put("IntrBkSttlmAmt", "Settlement amount is required");
        } else {
            currency = trimToNull(transaction.interbankSettlementAmount.currency);
            if (currency == null || !CURRENCY_PATTERN.matcher(currency).matches()) {
                violations.put("IntrBkSttlmAmt/@Ccy", "Currency must be a 3-letter uppercase ISO 4217 code");
            }
            amount = parseAmount(transaction.interbankSettlementAmount.value, violations);
        }

        // --- debtor / creditor (parties + accounts) -------------------------------------------
        String debtorName = requireName(transaction.debtor == null ? null : transaction.debtor.name,
                "Dbtr/Nm", "Debtor name is required", violations);
        String creditorName = requireName(transaction.creditor == null ? null : transaction.creditor.name,
                "Cdtr/Nm", "Creditor name is required", violations);

        String debtorAccount = requireAccount(
                transaction.debtorAccount == null ? null : transaction.debtorAccount.number(),
                "DbtrAcct", "Debtor account is required", violations);
        String creditorAccount = requireAccount(
                transaction.creditorAccount == null ? null : transaction.creditorAccount.number(),
                "CdtrAcct", "Creditor account is required", violations);

        // --- agents -> participants ---------------------------------------------------------
        UUID senderId = resolveParticipant(
                transaction.debtorAgent == null ? null : transaction.debtorAgent.memberId(),
                "DbtrAgt", "sender", violations);
        UUID destinationId = resolveParticipant(
                transaction.creditorAgent == null ? null : transaction.creditorAgent.memberId(),
                "CdtrAgt", "destination", violations);

        if (!violations.isEmpty()) {
            throw failure(violations);
        }

        return new InitiatePaymentRequest(
                senderId,
                destinationId,
                debtorAccount,
                creditorAccount,
                amount,
                currency,
                endToEndId,
                uetr,
                debtorName,
                creditorName
        );
    }

    /** The message must contain exactly one credit transfer transaction. */
    private CreditTransferTransaction findSingleTransaction(Pacs008Document document, Map<String, String> violations) {

        FIToFICustomerCreditTransfer transfer = document.fiToFiCustomerCreditTransfer;

        if (transfer == null) {
            violations.put("FIToFICstmrCdtTrf", "Message body is required");
            return null;
        }

        if (transfer.groupHeader == null || trimToNull(transfer.groupHeader.messageId) == null) {
            violations.put("GrpHdr/MsgId", "Message id is required");
        }

        if (transfer.creditTransferTransactions.size() != 1) {
            violations.put("CdtTrfTxInf",
                    "Exactly one credit transfer transaction is supported, found "
                            + transfer.creditTransferTransactions.size());
            return null;
        }

        if (transfer.groupHeader != null
                && transfer.groupHeader.numberOfTransactions != null
                && !"1".equals(transfer.groupHeader.numberOfTransactions.trim())) {
            violations.put("GrpHdr/NbOfTxs", "NbOfTxs must be 1");
        }

        return transfer.creditTransferTransactions.get(0);
    }

    private BigDecimal parseAmount(String raw, Map<String, String> violations) {

        String text = trimToNull(raw);

        if (text == null) {
            violations.put("IntrBkSttlmAmt", "Settlement amount is required");
            return null;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(text);
        } catch (NumberFormatException exception) {
            violations.put("IntrBkSttlmAmt", "Settlement amount is not a valid number");
            return null;
        }

        if (amount.signum() <= 0) {
            violations.put("IntrBkSttlmAmt", "Settlement amount must be greater than zero");
            return null;
        }

        if (amount.stripTrailingZeros().scale() > MAX_AMOUNT_SCALE) {
            violations.put("IntrBkSttlmAmt",
                    "Settlement amount supports at most " + MAX_AMOUNT_SCALE + " decimal places");
            return null;
        }

        return amount;
    }

    private String requireName(String raw, String path, String message, Map<String, String> violations) {

        String name = trimToNull(raw);

        if (name == null) {
            violations.put(path, message);
        } else if (name.length() > MAX_NAME) {
            violations.put(path, "Name must not exceed 140 characters");
        }
        return name;
    }

    private String requireAccount(String raw, String path, String message, Map<String, String> violations) {

        String account = trimToNull(raw);

        if (account == null) {
            violations.put(path, message);
        } else if (account.length() > MAX_ACCOUNT) {
            violations.put(path, "Account must not exceed 100 characters");
        }
        return account;
    }

    /** Looks up the agent's member id (= participant code) and returns the participant id. */
    private UUID resolveParticipant(String memberId, String path, String role, Map<String, String> violations) {

        String code = trimToNull(memberId);

        if (code == null) {
            violations.put(path, "The " + role + " agent (FinInstnId/ClrSysMmbId/MmbId) is required");
            return null;
        }

        // Participant codes are stored trimmed and upper-cased (see ParticipantService).
        return participantRepository.findByCode(code.toUpperCase(Locale.ROOT))
                .map(Participant::getId)
                .orElseGet(() -> {
                    violations.put(path, "Unknown " + role + " participant code '" + code + "'");
                    return null;
                });
    }

    private Iso20022ValidationException failure(Map<String, String> violations) {
        return new Iso20022ValidationException(
                "ISO 20022 pacs.008 message is invalid (" + violations.size() + " problem(s))",
                violations);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
