package com.sahanswitch.iso20022.application;

import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.Account;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.Agent;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.Amount;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.CreditTransferTransaction;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.FIToFICustomerCreditTransfer;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.GroupHeader;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.Party;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.PaymentIdentification;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document.SettlementInstruction;
import com.sahanswitch.iso20022.infrastructure.Iso20022XmlSupport;
import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import jakarta.xml.bind.JAXBContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Generates ISO 20022 <b>pacs.008.001.10</b> (FIToFICustomerCreditTransfer) XML for a payment.
 *
 * <p><b>Task 4.1.</b> This is the message the switch forwards to the destination participant
 * (or hands back to a client that wants the standard representation of a payment). The
 * mapping from the payment is:
 * <ul>
 *   <li>{@code DbtrAgt} = sender participant code, {@code CdtrAgt} = destination participant code</li>
 *   <li>{@code DbtrAcct} / {@code CdtrAcct} = source / destination account</li>
 *   <li>{@code IntrBkSttlmAmt} = amount (with the currency as the {@code Ccy} attribute)</li>
 *   <li>{@code EndToEndId} and {@code UETR} = the identifiers stored with the payment</li>
 *   <li>{@code Dbtr/Nm}, {@code Cdtr/Nm} = stored names, or {@code NOTPROVIDED} (the ISO
 *       convention for a mandatory text we do not have) for payments created through JSON</li>
 * </ul>
 */
@Service
public class Iso20022Pacs008Service {

    /** ISO convention for a mandatory field whose value is unknown. */
    static final String NOT_PROVIDED = "NOTPROVIDED";

    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final PaymentRepository paymentRepository;

    /** JAXB contexts are expensive to create and thread-safe, so build one and reuse it. */
    private final JAXBContext context = Iso20022XmlSupport.newContext(Pacs008Document.class);

    public Iso20022Pacs008Service(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /** Loads the payment and renders its pacs.008 XML. */
    @Transactional(readOnly = true)
    public String generate(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        return generate(payment);
    }

    /**
     * Renders the pacs.008 XML of an already loaded payment. The caller must keep the
     * persistence session open, because the participants are loaded lazily.
     */
    public String generate(Payment payment) {
        return Iso20022XmlSupport.marshal(context, toDocument(payment));
    }

    /** Builds the message object (also used directly by tests). */
    Pacs008Document toDocument(Payment payment) {

        String sender = payment.getSenderParticipant().getCode();
        // Legacy payments (created before V6) have no destination participant.
        String destination = payment.getDestinationParticipant() == null
                ? NOT_PROVIDED
                : payment.getDestinationParticipant().getCode();

        // --- group header: one message, one transaction -------------------------------
        GroupHeader header = new GroupHeader();
        header.messageId = payment.getPaymentReference();
        header.creationDateTime = formatDateTime(payment.getCreatedAt());
        header.numberOfTransactions = "1";
        header.settlementInformation = new SettlementInstruction();
        header.settlementInformation.settlementMethod = "CLRG";
        header.instructingAgent = Agent.ofMemberId(sender);
        header.instructedAgent = Agent.ofMemberId(destination);

        // --- the single credit transfer transaction ----------------------------------
        PaymentIdentification paymentId = new PaymentIdentification();
        paymentId.instructionId = payment.getPaymentReference();
        paymentId.endToEndId = payment.getEndToEndId() != null
                ? payment.getEndToEndId()
                : payment.getPaymentReference();
        paymentId.transactionId = payment.getPaymentReference();
        paymentId.uetr = payment.getUetr() == null ? null : payment.getUetr().toString();

        Amount amount = new Amount();
        amount.currency = payment.getCurrency();
        // toPlainString: never scientific notation; stripTrailingZeros: "100.5" not "100.5000"
        amount.value = payment.getAmount().stripTrailingZeros().toPlainString();

        CreditTransferTransaction transaction = new CreditTransferTransaction();
        transaction.paymentIdentification = paymentId;
        transaction.interbankSettlementAmount = amount;
        transaction.chargeBearer = "SLEV";
        transaction.debtor = party(payment.getDebtorName());
        transaction.debtorAccount = Account.ofNumber(payment.getSourceAccount());
        transaction.debtorAgent = Agent.ofMemberId(sender);
        transaction.creditorAgent = Agent.ofMemberId(destination);
        transaction.creditor = party(payment.getCreditorName());
        transaction.creditorAccount = Account.ofNumber(payment.getDestinationAccount());

        FIToFICustomerCreditTransfer transfer = new FIToFICustomerCreditTransfer();
        transfer.groupHeader = header;
        transfer.creditTransferTransactions.add(transaction);

        Pacs008Document document = new Pacs008Document();
        document.fiToFiCustomerCreditTransfer = transfer;
        return document;
    }

    private Party party(String name) {
        Party party = new Party();
        party.name = (name == null || name.isBlank()) ? NOT_PROVIDED : name;
        return party;
    }

    /** ISODateTime in UTC, millisecond precision, e.g. 2026-10-05T07:00:00.123Z. */
    static String formatDateTime(Instant instant) {
        Instant value = instant != null ? instant : Instant.now();
        return OffsetDateTime.ofInstant(value.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC)
                .format(ISO_DATE_TIME);
    }
}
