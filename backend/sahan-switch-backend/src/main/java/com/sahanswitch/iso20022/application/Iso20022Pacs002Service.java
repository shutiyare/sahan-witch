package com.sahanswitch.iso20022.application;

import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.iso20022.domain.pacs002.Pacs002Document;
import com.sahanswitch.iso20022.domain.pacs002.Pacs002Document.Agent;
import com.sahanswitch.iso20022.domain.pacs002.Pacs002Document.FIToFIPaymentStatusReport;
import com.sahanswitch.iso20022.domain.pacs002.Pacs002Document.GroupHeader;
import com.sahanswitch.iso20022.domain.pacs002.Pacs002Document.OriginalGroupInformation;
import com.sahanswitch.iso20022.domain.pacs002.Pacs002Document.PaymentTransaction;
import com.sahanswitch.iso20022.domain.pacs002.Pacs002Document.Reason;
import com.sahanswitch.iso20022.domain.pacs002.Pacs002Document.StatusReasonInformation;
import com.sahanswitch.iso20022.infrastructure.Iso20022XmlSupport;
import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.payment.domain.PaymentStatus;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import jakarta.xml.bind.JAXBContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Generates ISO 20022 <b>pacs.002.001.10</b> (FIToFIPaymentStatusReport) XML for a payment.
 *
 * <p><b>Task 4.1.</b> The status report is the answer to a pacs.008: it confirms (ACSC),
 * acknowledges (ACCP / ACSP) or rejects (RJCT) the payment. Status mapping:
 * <ul>
 *   <li>{@code ACCEPTED}   -> {@code ACCP} accepted, not yet processed</li>
 *   <li>{@code PROCESSING} -> {@code ACSP} accepted, settlement in process</li>
 *   <li>{@code COMPLETED}  -> {@code ACSC} accepted, settlement completed</li>
 *   <li>{@code FAILED}     -> {@code RJCT} rejected, with {@code StsRsnInf} explaining why</li>
 *   <li>{@code PENDING}    -> {@code PDNG} pending</li>
 * </ul>
 */
@Service
public class Iso20022Pacs002Service {

    /** ISO ExternalStatusReason1Code "AB05": timeout of the creditor agent. */
    static final String REASON_TIMEOUT = "AB05";

    /** ISO ExternalStatusReason1Code "NARR": the reason is given as narrative text. */
    static final String REASON_NARRATIVE = "NARR";

    /** AddtlInf is Max105Text in the standard. */
    private static final int MAX_ADDITIONAL_INFO = 105;

    private static final String ORIGINAL_MESSAGE_NAME = "pacs.008.001.10";

    private final PaymentRepository paymentRepository;

    private final JAXBContext context = Iso20022XmlSupport.newContext(Pacs002Document.class);

    public Iso20022Pacs002Service(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /** Loads the payment and renders its pacs.002 status report. */
    @Transactional(readOnly = true)
    public String generate(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        return generate(payment);
    }

    /** Renders the status report of an already loaded payment (session must still be open). */
    public String generate(Payment payment) {
        return Iso20022XmlSupport.marshal(context, toDocument(payment));
    }

    /** Maps the internal status to the ISO 20022 transaction status code. */
    public static String toIsoStatus(PaymentStatus status) {
        return switch (status) {
            case ACCEPTED -> "ACCP";
            case PROCESSING -> "ACSP";
            case COMPLETED -> "ACSC";
            case FAILED -> "RJCT";
            case PENDING -> "PDNG";
        };
    }

    Pacs002Document toDocument(Payment payment) {

        String sender = payment.getSenderParticipant().getCode();
        String destination = payment.getDestinationParticipant() == null
                ? Iso20022Pacs008Service.NOT_PROVIDED
                : payment.getDestinationParticipant().getCode();

        // The destination answers, the sender receives the answer.
        GroupHeader header = new GroupHeader();
        header.messageId = "SHN-STS-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
        header.creationDateTime = Iso20022Pacs008Service.formatDateTime(null);
        header.instructingAgent = Agent.ofMemberId(destination);
        header.instructedAgent = Agent.ofMemberId(sender);

        // Which message this report refers to (matches Iso20022Pacs008Service).
        OriginalGroupInformation original = new OriginalGroupInformation();
        original.originalMessageId = payment.getPaymentReference();
        original.originalMessageNameId = ORIGINAL_MESSAGE_NAME;

        PaymentTransaction transaction = new PaymentTransaction();
        transaction.statusId = payment.getPaymentReference();
        transaction.originalInstructionId = payment.getPaymentReference();
        transaction.originalEndToEndId = payment.getEndToEndId() != null
                ? payment.getEndToEndId()
                : payment.getPaymentReference();
        transaction.originalTransactionId = payment.getPaymentReference();
        transaction.originalUetr = payment.getUetr() == null ? null : payment.getUetr().toString();
        transaction.transactionStatus = toIsoStatus(payment.getStatus());

        if (payment.getStatus() == PaymentStatus.FAILED) {
            // A rejection must say why.
            transaction.statusReasonInformation.add(rejectionReason(payment.getFailureReason()));
        } else {
            transaction.acceptanceDateTime = Iso20022Pacs008Service.formatDateTime(payment.getUpdatedAt());
        }

        FIToFIPaymentStatusReport report = new FIToFIPaymentStatusReport();
        report.groupHeader = header;
        report.originalGroupInformation.add(original);
        report.transactionInformationAndStatus.add(transaction);

        Pacs002Document document = new Pacs002Document();
        document.paymentStatusReport = report;
        return document;
    }

    private StatusReasonInformation rejectionReason(String failureReason) {

        String text = (failureReason == null || failureReason.isBlank()) ? "Unknown failure" : failureReason;

        Reason reason = new Reason();
        reason.code = text.startsWith("Participant timeout") || text.contains("unavailable")
                ? REASON_TIMEOUT
                : REASON_NARRATIVE;

        StatusReasonInformation information = new StatusReasonInformation();
        information.reason = reason;
        information.additionalInformation.add(
                text.length() <= MAX_ADDITIONAL_INFO ? text : text.substring(0, MAX_ADDITIONAL_INFO));
        return information;
    }
}
