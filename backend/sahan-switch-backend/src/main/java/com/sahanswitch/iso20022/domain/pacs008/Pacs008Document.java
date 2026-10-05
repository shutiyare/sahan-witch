package com.sahanswitch.iso20022.domain.pacs008;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.XmlValue;

import java.util.ArrayList;
import java.util.List;

/**
 * JAXB model of an ISO 20022 <b>pacs.008.001.10</b> message: the FIToFICustomerCreditTransfer
 * a financial institution sends to ask another one to credit a customer.
 *
 * <p><b>Task 4.1.</b> This is a hand-written <i>subset</i> of the standard: the elements the
 * switch needs to move a payment (group header, payment identification, settlement amount,
 * debtor, creditor and their agents). Element names and their order follow the official
 * schema, so a message produced here has the right shape for a real counterpart. It is not
 * validated against the official XSD, which is not part of this repository.
 *
 * <p>These classes are plain binding structures for JAXB, so their fields are public and
 * carry no logic. Business rules live in {@code Iso20022MessageTransformer}.
 *
 * <p>Participant identification: an agent (bank / wallet) is identified by its
 * clearing-system member id ({@code FinInstnId/ClrSysMmbId/MmbId}), which carries the
 * Sahan Switch participant <i>code</i>.
 */
@XmlRootElement(name = "Document")
@XmlAccessorType(XmlAccessType.FIELD)
public class Pacs008Document {

    @XmlElement(name = "FIToFICstmrCdtTrf")
    public FIToFICustomerCreditTransfer fiToFiCustomerCreditTransfer;

    // ------------------------------------------------------------------------------------

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "FIToFICustomerCreditTransferV10",
            propOrder = {"groupHeader", "creditTransferTransactions"})
    public static class FIToFICustomerCreditTransfer {

        @XmlElement(name = "GrpHdr")
        public GroupHeader groupHeader;

        @XmlElement(name = "CdtTrfTxInf")
        public List<CreditTransferTransaction> creditTransferTransactions = new ArrayList<>();
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "GroupHeader96",
            propOrder = {"messageId", "creationDateTime", "numberOfTransactions",
                    "settlementInformation", "instructingAgent", "instructedAgent"})
    public static class GroupHeader {

        /** Unique id of the message (Max35Text). */
        @XmlElement(name = "MsgId")
        public String messageId;

        /** ISODateTime, e.g. 2026-10-05T07:00:00.000Z. */
        @XmlElement(name = "CreDtTm")
        public String creationDateTime;

        /** Number of transactions in the message. The switch handles exactly one. */
        @XmlElement(name = "NbOfTxs")
        public String numberOfTransactions;

        @XmlElement(name = "SttlmInf")
        public SettlementInstruction settlementInformation;

        @XmlElement(name = "InstgAgt")
        public Agent instructingAgent;

        @XmlElement(name = "InstdAgt")
        public Agent instructedAgent;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "SettlementInstruction7", propOrder = {"settlementMethod"})
    public static class SettlementInstruction {

        /** INDA, INGA, COVE or CLRG. The switch is a clearing system, so CLRG. */
        @XmlElement(name = "SttlmMtd")
        public String settlementMethod;
    }

    /** BranchAndFinancialInstitutionIdentification: who a bank / wallet is. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "BranchAndFinancialInstitutionIdentification6", propOrder = {"financialInstitution"})
    public static class Agent {

        @XmlElement(name = "FinInstnId")
        public FinancialInstitution financialInstitution;

        public Agent() {
        }

        /** Builds an agent identified by the given clearing-system member id (participant code). */
        public static Agent ofMemberId(String memberId) {
            Agent agent = new Agent();
            agent.financialInstitution = new FinancialInstitution();
            agent.financialInstitution.clearingSystemMember = new ClearingSystemMember();
            agent.financialInstitution.clearingSystemMember.memberId = memberId;
            return agent;
        }

        /** The member id (participant code), or {@code null} when it is not present. */
        public String memberId() {
            return financialInstitution == null || financialInstitution.clearingSystemMember == null
                    ? null
                    : financialInstitution.clearingSystemMember.memberId;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "FinancialInstitutionIdentification18", propOrder = {"clearingSystemMember"})
    public static class FinancialInstitution {

        @XmlElement(name = "ClrSysMmbId")
        public ClearingSystemMember clearingSystemMember;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "ClearingSystemMemberIdentification2", propOrder = {"memberId"})
    public static class ClearingSystemMember {

        @XmlElement(name = "MmbId")
        public String memberId;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "CreditTransferTransaction50",
            propOrder = {"paymentIdentification", "interbankSettlementAmount", "chargeBearer",
                    "debtor", "debtorAccount", "debtorAgent", "creditorAgent", "creditor", "creditorAccount"})
    public static class CreditTransferTransaction {

        @XmlElement(name = "PmtId")
        public PaymentIdentification paymentIdentification;

        @XmlElement(name = "IntrBkSttlmAmt")
        public Amount interbankSettlementAmount;

        /** DEBT, CRED, SHAR or SLEV. */
        @XmlElement(name = "ChrgBr")
        public String chargeBearer;

        @XmlElement(name = "Dbtr")
        public Party debtor;

        @XmlElement(name = "DbtrAcct")
        public Account debtorAccount;

        /** The debtor's bank: the SENDER participant. */
        @XmlElement(name = "DbtrAgt")
        public Agent debtorAgent;

        /** The creditor's bank: the DESTINATION participant. */
        @XmlElement(name = "CdtrAgt")
        public Agent creditorAgent;

        @XmlElement(name = "Cdtr")
        public Party creditor;

        @XmlElement(name = "CdtrAcct")
        public Account creditorAccount;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "PaymentIdentification7",
            propOrder = {"instructionId", "endToEndId", "transactionId", "uetr"})
    public static class PaymentIdentification {

        @XmlElement(name = "InstrId")
        public String instructionId;

        /** Reference the original sender gave the payment; must survive the whole chain. */
        @XmlElement(name = "EndToEndId")
        public String endToEndId;

        @XmlElement(name = "TxId")
        public String transactionId;

        /** Unique end-to-end transaction reference (UUID v4). */
        @XmlElement(name = "UETR")
        public String uetr;
    }

    /**
     * Amount with its currency as attribute: {@code <IntrBkSttlmAmt Ccy="USD">100.50</...>}.
     * The value is kept as text so it is never written in scientific notation.
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "ActiveCurrencyAndAmount")
    public static class Amount {

        @XmlAttribute(name = "Ccy")
        public String currency;

        @XmlValue
        public String value;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "PartyIdentification135", propOrder = {"name"})
    public static class Party {

        @XmlElement(name = "Nm")
        public String name;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "CashAccount40", propOrder = {"identification"})
    public static class Account {

        @XmlElement(name = "Id")
        public AccountIdentification identification;

        /** Builds an account identified by a proprietary account number ({@code Id/Othr/Id}). */
        public static Account ofNumber(String accountNumber) {
            Account account = new Account();
            account.identification = new AccountIdentification();
            account.identification.other = new OtherAccountIdentification();
            account.identification.other.id = accountNumber;
            return account;
        }

        /** The account number (IBAN or proprietary id), or {@code null} when absent. */
        public String number() {
            if (identification == null) {
                return null;
            }
            if (identification.iban != null && !identification.iban.isBlank()) {
                return identification.iban;
            }
            return identification.other == null ? null : identification.other.id;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "AccountIdentification4Choice", propOrder = {"iban", "other"})
    public static class AccountIdentification {

        @XmlElement(name = "IBAN")
        public String iban;

        @XmlElement(name = "Othr")
        public OtherAccountIdentification other;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "GenericAccountIdentification1", propOrder = {"id"})
    public static class OtherAccountIdentification {

        @XmlElement(name = "Id")
        public String id;
    }
}
