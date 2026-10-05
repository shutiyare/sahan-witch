package com.sahanswitch.iso20022.application;

import com.sahanswitch.iso20022.domain.Iso20022ValidationException;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.payment.api.InitiatePaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static com.sahanswitch.iso20022.application.Iso20022Fixtures.VALID_UETR;
import static com.sahanswitch.iso20022.application.Iso20022Fixtures.validPacs008;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 6 / Task 4.2: parsing and validation of incoming pacs.008 messages, including the
 * XML security checks.
 */
class Iso20022MessageTransformerTest {

    private final UUID senderId = UUID.randomUUID();
    private final UUID destinationId = UUID.randomUUID();

    private Iso20022MessageTransformer transformer;

    @BeforeEach
    void setUp() {
        ParticipantRepository repository = mock(ParticipantRepository.class);

        Participant sender = mock(Participant.class);
        when(sender.getId()).thenReturn(senderId);
        Participant destination = mock(Participant.class);
        when(destination.getId()).thenReturn(destinationId);

        when(repository.findByCode("SENDERBANK")).thenReturn(Optional.of(sender));
        when(repository.findByCode("DESTWALLET")).thenReturn(Optional.of(destination));
        when(repository.findByCode(org.mockito.ArgumentMatchers.argThat(code ->
                !"SENDERBANK".equals(code) && !"DESTWALLET".equals(code)))).thenReturn(Optional.empty());

        transformer = new Iso20022MessageTransformer(repository);
    }

    /** Parses the XML and returns the violations, failing the test if no exception is thrown. */
    private Iso20022ValidationException rejected(String xml) {
        return assertThrows(Iso20022ValidationException.class, () -> transformer.parsePacs008(xml));
    }

    private String without(String xmlPart) {
        String xml = validPacs008();
        assertTrue(xml.contains(xmlPart), "fixture does not contain: " + xmlPart);
        return xml.replace(xmlPart, "");
    }

    // ================================================================ happy path

    @Test
    void validMessageIsMappedToAnInitiatePaymentRequest() {
        InitiatePaymentRequest request = transformer.parsePacs008(validPacs008());

        assertEquals(senderId, request.senderParticipantId());
        assertEquals(destinationId, request.destinationParticipantId());
        assertEquals("ACC-111", request.sourceAccount());
        assertEquals("ACC-222", request.destinationAccount());
        assertEquals(0, new BigDecimal("100.50").compareTo(request.amount()));
        assertEquals("USD", request.currency());
        assertEquals("E2E-0001", request.endToEndId());
        assertEquals(UUID.fromString(VALID_UETR), request.uetr());
        assertEquals("Alice Debtor", request.debtorName());
        assertEquals("Bob Creditor", request.creditorName());
    }

    @Test
    void participantCodesAreMatchedCaseInsensitively() {
        String xml = validPacs008().replace("SENDERBANK", "senderbank");

        assertEquals(senderId, transformer.parsePacs008(xml).senderParticipantId());
    }

    @Test
    void ibanIsAcceptedAsAccountIdentification() {
        String xml = validPacs008().replace(
                "<DbtrAcct><Id><Othr><Id>ACC-111</Id></Othr></Id></DbtrAcct>",
                "<DbtrAcct><Id><IBAN>SO211000001234567890</IBAN></Id></DbtrAcct>");

        assertEquals("SO211000001234567890", transformer.parsePacs008(xml).sourceAccount());
    }

    @Test
    void uppercaseUetrIsNormalised() {
        String xml = validPacs008().replace(VALID_UETR, VALID_UETR.toUpperCase());

        assertEquals(UUID.fromString(VALID_UETR), transformer.parsePacs008(xml).uetr());
    }

    @Test
    void amountWithTrailingZerosAndUpToFourDecimalsIsAccepted() {
        assertEquals(0, new BigDecimal("100.5").compareTo(
                transformer.parsePacs008(validPacs008().replace(">100.50<", ">100.5000<")).amount()));
        assertEquals(0, new BigDecimal("0.0001").compareTo(
                transformer.parsePacs008(validPacs008().replace(">100.50<", ">0.0001<")).amount()));
    }

    // ================================================================ mandatory fields

    @Test
    void missingEndToEndIdIsRejected() {
        Iso20022ValidationException exception = rejected(without("<EndToEndId>E2E-0001</EndToEndId>"));

        assertTrue(exception.getViolations().containsKey("PmtId/EndToEndId"), exception.getViolations().toString());
    }

    @Test
    void tooLongEndToEndIdIsRejected() {
        String xml = validPacs008().replace("E2E-0001", "E".repeat(36));

        assertTrue(rejected(xml).getViolations().containsKey("PmtId/EndToEndId"));
    }

    @Test
    void missingUetrIsRejected() {
        Iso20022ValidationException exception = rejected(without("<UETR>" + VALID_UETR + "</UETR>"));

        assertEquals("UETR is required", exception.getViolations().get("PmtId/UETR"));
    }

    @Test
    void uetrThatIsNotAVersion4UuidIsRejected() {
        // version nibble is 1, not 4
        String xml = validPacs008().replace(VALID_UETR, "8a562c67-ca16-18ba-b074-65581be6f011");
        assertEquals("UETR must be a UUID version 4", rejected(xml).getViolations().get("PmtId/UETR"));

        assertTrue(rejected(validPacs008().replace(VALID_UETR, "not-a-uuid")).getViolations().containsKey("PmtId/UETR"));
    }

    @Test
    void missingSettlementAmountIsRejected() {
        Iso20022ValidationException exception = rejected(without("<IntrBkSttlmAmt Ccy=\"USD\">100.50</IntrBkSttlmAmt>"));

        assertTrue(exception.getViolations().containsKey("IntrBkSttlmAmt"));
    }

    @Test
    void invalidAmountsAreRejected() {
        for (String bad : new String[]{"0", "-5", "abc", "", "1.23456", "1E3x"}) {
            String xml = validPacs008().replace(">100.50<", ">" + bad + "<");
            assertTrue(rejected(xml).getViolations().containsKey("IntrBkSttlmAmt"), "amount '" + bad + "' must be rejected");
        }
    }

    @Test
    void missingOrInvalidCurrencyIsRejected() {
        assertTrue(rejected(validPacs008().replace(" Ccy=\"USD\"", "")).getViolations().containsKey("IntrBkSttlmAmt/@Ccy"));
        assertTrue(rejected(validPacs008().replace("Ccy=\"USD\"", "Ccy=\"usd\"")).getViolations().containsKey("IntrBkSttlmAmt/@Ccy"));
        assertTrue(rejected(validPacs008().replace("Ccy=\"USD\"", "Ccy=\"DOLLAR\"")).getViolations().containsKey("IntrBkSttlmAmt/@Ccy"));
    }

    @Test
    void missingDebtorIsRejected() {
        assertEquals("Debtor name is required",
                rejected(without("<Dbtr><Nm>Alice Debtor</Nm></Dbtr>")).getViolations().get("Dbtr/Nm"));
    }

    @Test
    void missingCreditorIsRejected() {
        assertEquals("Creditor name is required",
                rejected(without("<Cdtr><Nm>Bob Creditor</Nm></Cdtr>")).getViolations().get("Cdtr/Nm"));
    }

    @Test
    void blankDebtorNameIsRejected() {
        assertTrue(rejected(validPacs008().replace("Alice Debtor", "   ")).getViolations().containsKey("Dbtr/Nm"));
    }

    @Test
    void missingAccountsAreRejected() {
        assertTrue(rejected(without("<DbtrAcct><Id><Othr><Id>ACC-111</Id></Othr></Id></DbtrAcct>"))
                .getViolations().containsKey("DbtrAcct"));
        assertTrue(rejected(without("<CdtrAcct><Id><Othr><Id>ACC-222</Id></Othr></Id></CdtrAcct>"))
                .getViolations().containsKey("CdtrAcct"));
    }

    @Test
    void missingAgentsAreRejected() {
        assertTrue(rejected(without(Iso20022Fixtures.agent("DbtrAgt", "SENDERBANK")))
                .getViolations().containsKey("DbtrAgt"));
        assertTrue(rejected(without(Iso20022Fixtures.agent("CdtrAgt", "DESTWALLET")))
                .getViolations().containsKey("CdtrAgt"));
    }

    @Test
    void unknownParticipantCodeIsRejected() {
        Iso20022ValidationException exception = rejected(validPacs008().replace("DESTWALLET", "NOSUCHBANK"));

        assertTrue(exception.getViolations().get("CdtrAgt").contains("NOSUCHBANK"));
    }

    @Test
    void missingMessageIdIsRejected() {
        assertTrue(rejected(without("<MsgId>MSG-0001</MsgId>")).getViolations().containsKey("GrpHdr/MsgId"));
    }

    @Test
    void allProblemsAreReportedTogether() {
        String xml = validPacs008()
                .replace("<EndToEndId>E2E-0001</EndToEndId>", "")
                .replace("<UETR>" + VALID_UETR + "</UETR>", "")
                .replace("<Dbtr><Nm>Alice Debtor</Nm></Dbtr>", "")
                .replace(">100.50<", ">-1<");

        Iso20022ValidationException exception = rejected(xml);

        assertTrue(exception.getViolations().size() >= 4, exception.getViolations().toString());
        assertTrue(exception.getViolations().keySet().containsAll(
                java.util.List.of("PmtId/EndToEndId", "PmtId/UETR", "Dbtr/Nm", "IntrBkSttlmAmt")));
    }

    // ================================================================ one transaction per message

    @Test
    void messagesWithSeveralTransactionsAreRejected() {
        String xml = validPacs008();
        String transaction = xml.substring(xml.indexOf("<CdtTrfTxInf>"), xml.indexOf("</CdtTrfTxInf>") + "</CdtTrfTxInf>".length());
        String twoTransactions = xml.replace(transaction, transaction + transaction).replace("<NbOfTxs>1</NbOfTxs>", "<NbOfTxs>2</NbOfTxs>");

        assertTrue(rejected(twoTransactions).getViolations().containsKey("CdtTrfTxInf"));
    }

    @Test
    void numberOfTransactionsMustBeOne() {
        assertTrue(rejected(validPacs008().replace("<NbOfTxs>1</NbOfTxs>", "<NbOfTxs>5</NbOfTxs>"))
                .getViolations().containsKey("GrpHdr/NbOfTxs"));
    }

    // ================================================================ malformed / hostile XML

    @Test
    void emptyAndNullInputAreRejected() {
        assertThrows(Iso20022ValidationException.class, () -> transformer.parsePacs008(null));
        assertThrows(Iso20022ValidationException.class, () -> transformer.parsePacs008(""));
        assertThrows(Iso20022ValidationException.class, () -> transformer.parsePacs008("   "));
    }

    @Test
    void malformedXmlIsRejected() {
        rejected("<Document><unclosed>");
        rejected("this is not xml at all");
    }

    @Test
    void wrongNamespaceOrRootElementIsRejected() {
        rejected(validPacs008().replace("pacs.008.001.10", "pacs.002.001.10"));
        rejected(validPacs008().replace("<Document", "<Other").replace("</Document>", "</Other>"));
    }

    @Test
    void externalEntityAttackIsRejected() {
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE Document [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<Document xmlns=\"" + Iso20022Fixtures.NAMESPACE + "\"><FIToFICstmrCdtTrf>&xxe;</FIToFICstmrCdtTrf></Document>";

        Iso20022ValidationException exception = rejected(xxe);

        assertTrue(exception.getMessage().contains("DOCTYPE"), exception.getMessage());
    }

    @Test
    void entityExpansionAttackIsRejected() {
        String billionLaughs = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;\">]>"
                + "<lolz>&lol2;</lolz>";

        rejected(billionLaughs);
    }

    @Test
    void oversizedInputIsRejectedBeforeParsing() {
        String huge = validPacs008().replace("Alice Debtor", "A".repeat(1_100_000));

        Iso20022ValidationException exception = rejected(huge);

        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("too large"));
    }
}
