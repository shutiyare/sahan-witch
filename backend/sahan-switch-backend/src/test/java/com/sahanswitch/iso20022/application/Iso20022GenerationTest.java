package com.sahanswitch.iso20022.application;

import com.sahanswitch.iso20022.domain.pacs002.Pacs002Document;
import com.sahanswitch.iso20022.domain.pacs008.Pacs008Document;
import com.sahanswitch.iso20022.infrastructure.Iso20022XmlSupport;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.domain.ParticipantStatus;
import com.sahanswitch.participant.domain.ParticipantType;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.payment.api.InitiatePaymentRequest;
import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 6 / Task 4.1: generation of pacs.008 and pacs.002 XML, and the round trip
 * payment -> pacs.008 -> {@link InitiatePaymentRequest}.
 */
class Iso20022GenerationTest {

    private final Iso20022Pacs008Service pacs008 = new Iso20022Pacs008Service(null);
    private final Iso20022Pacs002Service pacs002 = new Iso20022Pacs002Service(null);

    private final UUID uetr = UUID.fromString("8a562c67-ca16-48ba-b074-65581be6f011");

    private Participant participant(String code) {
        return new Participant(code, code + " name", ParticipantType.BANK, ParticipantStatus.ACTIVE);
    }

    private Payment isoPayment() {
        return Payment.create(participant("SENDERBANK"), participant("DESTWALLET"), "ACC-111", "ACC-222",
                new BigDecimal("100.5000"), "USD", "key-1", "E2E-0001", uetr, "Alice Debtor", "Bob Creditor");
    }

    // ================================================================ pacs.008

    @Test
    void pacs008ContainsTheStandardNamespaceAndAllMappedValues() {
        String xml = pacs008.generate(isoPayment());

        assertTrue(xml.startsWith("<?xml"), xml);
        assertTrue(xml.contains("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10\">"), xml);
        assertTrue(xml.contains("<FIToFICstmrCdtTrf>"));
        assertTrue(xml.contains("<NbOfTxs>1</NbOfTxs>"));
        assertTrue(xml.contains("<SttlmMtd>CLRG</SttlmMtd>"));
        assertTrue(xml.contains("<EndToEndId>E2E-0001</EndToEndId>"));
        assertTrue(xml.contains("<UETR>" + uetr + "</UETR>"));
        // trailing zeros stripped, no scientific notation, currency as attribute
        assertTrue(xml.contains("<IntrBkSttlmAmt Ccy=\"USD\">100.5</IntrBkSttlmAmt>"), xml);
        assertTrue(xml.contains("<Nm>Alice Debtor</Nm>"));
        assertTrue(xml.contains("<Nm>Bob Creditor</Nm>"));
        assertTrue(xml.contains("<MmbId>SENDERBANK</MmbId>"));
        assertTrue(xml.contains("<MmbId>DESTWALLET</MmbId>"));
        assertTrue(xml.contains("<Id>ACC-111</Id>"));
        assertTrue(xml.contains("<Id>ACC-222</Id>"));
    }

    @Test
    void pacs008ElementsAppearInTheOfficialOrder() {
        String xml = pacs008.generate(isoPayment());

        int[] positions = {
                xml.indexOf("<PmtId>"), xml.indexOf("<IntrBkSttlmAmt"), xml.indexOf("<ChrgBr>"),
                xml.indexOf("<Dbtr>"), xml.indexOf("<DbtrAcct>"), xml.indexOf("<DbtrAgt>"),
                xml.indexOf("<CdtrAgt>"), xml.indexOf("<Cdtr>"), xml.indexOf("<CdtrAcct>")
        };

        for (int i = 0; i < positions.length; i++) {
            assertTrue(positions[i] >= 0, "element " + i + " missing");
            if (i > 0) {
                assertTrue(positions[i] > positions[i - 1], "element " + i + " is out of order");
            }
        }
    }

    @Test
    void largeAmountsAreNeverWrittenInScientificNotation() {
        Payment payment = Payment.create(participant("S"), participant("D"), "a", "b",
                new BigDecimal("1000000000.0000"), "USD", "k");

        assertTrue(pacs008.generate(payment).contains(">1000000000<"));
    }

    @Test
    void jsonPaymentWithoutNamesOrLegacyDestinationUsesNotProvided() {
        Payment plain = Payment.create(participant("S"), participant("D"), "a", "b", BigDecimal.TEN, "USD", "k");

        String xml = pacs008.generate(plain);

        assertTrue(xml.contains("<Nm>NOTPROVIDED</Nm>"), xml);
        // identifiers default sensibly: EndToEndId = payment reference, UETR generated
        assertTrue(xml.contains("<EndToEndId>" + plain.getPaymentReference() + "</EndToEndId>"));
        assertTrue(xml.contains("<UETR>" + plain.getUetr() + "</UETR>"));
    }

    @Test
    void specialCharactersInNamesAreEscaped() {
        Payment payment = Payment.create(participant("S"), participant("D"), "a", "b", BigDecimal.TEN, "USD",
                "k", null, null, "Tom & <Jerry>", "Bob");

        String xml = pacs008.generate(payment);

        assertTrue(xml.contains("Tom &amp; &lt;Jerry&gt;"), xml);
        // and it is still a valid, parseable document
        Pacs008Document parsed = Iso20022XmlSupport.unmarshal(
                Iso20022XmlSupport.newContext(Pacs008Document.class), xml, Pacs008Document.class);
        assertEquals("Tom & <Jerry>",
                parsed.fiToFiCustomerCreditTransfer.creditTransferTransactions.get(0).debtor.name);
    }

    @Test
    void roundTripPaymentToPacs008BackToRequestKeepsEverything() {
        Payment payment = isoPayment();

        ParticipantRepository repository = mock(ParticipantRepository.class);
        UUID senderId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        Participant sender = mock(Participant.class);
        when(sender.getId()).thenReturn(senderId);
        Participant destination = mock(Participant.class);
        when(destination.getId()).thenReturn(destinationId);
        when(repository.findByCode("SENDERBANK")).thenReturn(Optional.of(sender));
        when(repository.findByCode("DESTWALLET")).thenReturn(Optional.of(destination));

        InitiatePaymentRequest request =
                new Iso20022MessageTransformer(repository).parsePacs008(pacs008.generate(payment));

        assertEquals(senderId, request.senderParticipantId());
        assertEquals(destinationId, request.destinationParticipantId());
        assertEquals("ACC-111", request.sourceAccount());
        assertEquals("ACC-222", request.destinationAccount());
        assertEquals(0, payment.getAmount().compareTo(request.amount()));
        assertEquals("USD", request.currency());
        assertEquals("E2E-0001", request.endToEndId());
        assertEquals(uetr, request.uetr());
        assertEquals("Alice Debtor", request.debtorName());
        assertEquals("Bob Creditor", request.creditorName());
    }

    // ================================================================ pacs.002

    @Test
    void everyPaymentStatusMapsToTheRightIsoCode() {
        assertEquals("ACCP", Iso20022Pacs002Service.toIsoStatus(PaymentStatus.ACCEPTED));
        assertEquals("ACSP", Iso20022Pacs002Service.toIsoStatus(PaymentStatus.PROCESSING));
        assertEquals("ACSC", Iso20022Pacs002Service.toIsoStatus(PaymentStatus.COMPLETED));
        assertEquals("RJCT", Iso20022Pacs002Service.toIsoStatus(PaymentStatus.FAILED));
        assertEquals("PDNG", Iso20022Pacs002Service.toIsoStatus(PaymentStatus.PENDING));
        // guard: a new status added to the enum must also be mapped
        for (PaymentStatus status : PaymentStatus.values()) {
            assertNotNull(Iso20022Pacs002Service.toIsoStatus(status));
        }
    }

    private Pacs002Document parse002(String xml) {
        return Iso20022XmlSupport.unmarshal(
                Iso20022XmlSupport.newContext(Pacs002Document.class), xml, Pacs002Document.class);
    }

    @Test
    void completedPaymentProducesAcscWithoutReason() {
        Payment payment = isoPayment();
        payment.markProcessing();
        payment.markCompleted("EXT-1");

        String xml = pacs002.generate(payment);

        assertTrue(xml.contains("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10\">"), xml);
        assertTrue(xml.contains("<TxSts>ACSC</TxSts>"));
        assertFalse(xml.contains("<StsRsnInf>"), "accepted payments carry no rejection reason");
        assertTrue(xml.contains("<AccptncDtTm>"));
        assertTrue(xml.contains("<OrgnlEndToEndId>E2E-0001</OrgnlEndToEndId>"));
        assertTrue(xml.contains("<OrgnlUETR>" + uetr + "</OrgnlUETR>"));
        assertTrue(xml.contains("<OrgnlMsgNmId>pacs.008.001.10</OrgnlMsgNmId>"));
    }

    @Test
    void statusReportAnswersFromDestinationToSender() {
        Pacs002Document.GroupHeader header = parse002(pacs002.generate(isoPayment()))
                .paymentStatusReport.groupHeader;

        assertEquals("DESTWALLET", header.instructingAgent.financialInstitution.clearingSystemMember.memberId);
        assertEquals("SENDERBANK", header.instructedAgent.financialInstitution.clearingSystemMember.memberId);
        assertTrue(header.messageId.startsWith("SHN-STS-"));
        assertTrue(header.messageId.length() <= 35);
    }

    @Test
    void acceptedAndProcessingPaymentsProduceAccpAndAcsp() {
        Payment payment = isoPayment();
        assertTrue(pacs002.generate(payment).contains("<TxSts>ACCP</TxSts>"));

        payment.markProcessing();
        assertTrue(pacs002.generate(payment).contains("<TxSts>ACSP</TxSts>"));
    }

    @Test
    void rejectedPaymentProducesRjctWithNarrativeReason() {
        Payment payment = isoPayment();
        payment.markProcessing();
        payment.markFailed("Account closed");

        Pacs002Document.PaymentTransaction transaction =
                parse002(pacs002.generate(payment)).paymentStatusReport.transactionInformationAndStatus.get(0);

        assertEquals("RJCT", transaction.transactionStatus);
        assertEquals(1, transaction.statusReasonInformation.size());
        assertEquals("NARR", transaction.statusReasonInformation.get(0).reason.code);
        assertEquals("Account closed", transaction.statusReasonInformation.get(0).additionalInformation.get(0));
        assertNull(transaction.acceptanceDateTime);
    }

    @Test
    void timeoutRejectionUsesTheTimeoutReasonCode() {
        Payment payment = isoPayment();
        payment.markProcessing();
        payment.markFailed("Participant timeout: Participant DESTWALLET unavailable after 3 attempt(s)");

        Pacs002Document.PaymentTransaction transaction =
                parse002(pacs002.generate(payment)).paymentStatusReport.transactionInformationAndStatus.get(0);

        assertEquals("AB05", transaction.statusReasonInformation.get(0).reason.code);
    }

    @Test
    void longRejectionReasonIsTruncatedToTheIsoLimit() {
        Payment payment = isoPayment();
        payment.markFailed("x".repeat(500));

        Pacs002Document.PaymentTransaction transaction =
                parse002(pacs002.generate(payment)).paymentStatusReport.transactionInformationAndStatus.get(0);

        assertEquals(105, transaction.statusReasonInformation.get(0).additionalInformation.get(0).length());
    }
}
