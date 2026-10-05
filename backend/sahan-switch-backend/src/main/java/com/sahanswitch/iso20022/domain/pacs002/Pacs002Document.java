package com.sahanswitch.iso20022.domain.pacs002;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

import java.util.ArrayList;
import java.util.List;

/**
 * JAXB model of an ISO 20022 <b>pacs.002.001.10</b> message: the FIToFIPaymentStatusReport
 * that tells the sender what happened to a payment (accepted, settled, rejected, ...).
 *
 * <p><b>Task 4.1.</b> Like {@code Pacs008Document}, a hand-written subset that follows the
 * official element names and order but is not validated against the official XSD.
 * Plain public-field binding classes without logic.
 */
@XmlRootElement(name = "Document")
@XmlAccessorType(XmlAccessType.FIELD)
public class Pacs002Document {

    @XmlElement(name = "FIToFIPmtStsRpt")
    public FIToFIPaymentStatusReport paymentStatusReport;

    // ------------------------------------------------------------------------------------

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "FIToFIPaymentStatusReportV10",
            propOrder = {"groupHeader", "originalGroupInformation", "transactionInformationAndStatus"})
    public static class FIToFIPaymentStatusReport {

        @XmlElement(name = "GrpHdr")
        public GroupHeader groupHeader;

        @XmlElement(name = "OrgnlGrpInfAndSts")
        public List<OriginalGroupInformation> originalGroupInformation = new ArrayList<>();

        @XmlElement(name = "TxInfAndSts")
        public List<PaymentTransaction> transactionInformationAndStatus = new ArrayList<>();
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "GroupHeader91",
            propOrder = {"messageId", "creationDateTime", "instructingAgent", "instructedAgent"})
    public static class GroupHeader {

        @XmlElement(name = "MsgId")
        public String messageId;

        @XmlElement(name = "CreDtTm")
        public String creationDateTime;

        /** The party answering: the destination participant. */
        @XmlElement(name = "InstgAgt")
        public Agent instructingAgent;

        /** The party that receives the report: the sender participant. */
        @XmlElement(name = "InstdAgt")
        public Agent instructedAgent;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "BranchAndFinancialInstitutionIdentification6", propOrder = {"financialInstitution"})
    public static class Agent {

        @XmlElement(name = "FinInstnId")
        public FinancialInstitution financialInstitution;

        public static Agent ofMemberId(String memberId) {
            Agent agent = new Agent();
            agent.financialInstitution = new FinancialInstitution();
            agent.financialInstitution.clearingSystemMember = new ClearingSystemMember();
            agent.financialInstitution.clearingSystemMember.memberId = memberId;
            return agent;
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

    /** Identifies the original message this report is about. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "OriginalGroupHeader17", propOrder = {"originalMessageId", "originalMessageNameId"})
    public static class OriginalGroupInformation {

        @XmlElement(name = "OrgnlMsgId")
        public String originalMessageId;

        /** Always {@code pacs.008.001.10} here. */
        @XmlElement(name = "OrgnlMsgNmId")
        public String originalMessageNameId;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "PaymentTransaction110",
            propOrder = {"statusId", "originalInstructionId", "originalEndToEndId", "originalTransactionId",
                    "originalUetr", "transactionStatus", "statusReasonInformation", "acceptanceDateTime"})
    public static class PaymentTransaction {

        @XmlElement(name = "StsId")
        public String statusId;

        @XmlElement(name = "OrgnlInstrId")
        public String originalInstructionId;

        @XmlElement(name = "OrgnlEndToEndId")
        public String originalEndToEndId;

        @XmlElement(name = "OrgnlTxId")
        public String originalTransactionId;

        @XmlElement(name = "OrgnlUETR")
        public String originalUetr;

        /** ACCP, ACSP, ACSC, RJCT or PDNG. */
        @XmlElement(name = "TxSts")
        public String transactionStatus;

        /** Only present for rejected (RJCT) payments. */
        @XmlElement(name = "StsRsnInf")
        public List<StatusReasonInformation> statusReasonInformation = new ArrayList<>();

        @XmlElement(name = "AccptncDtTm")
        public String acceptanceDateTime;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "StatusReasonInformation12", propOrder = {"reason", "additionalInformation"})
    public static class StatusReasonInformation {

        @XmlElement(name = "Rsn")
        public Reason reason;

        /** Free text, each entry at most 105 characters (Max105Text). */
        @XmlElement(name = "AddtlInf")
        public List<String> additionalInformation = new ArrayList<>();
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "StatusReason6Choice", propOrder = {"code"})
    public static class Reason {

        /** ExternalStatusReason1Code, e.g. NARR (narrative) or AB05 (timeout creditor agent). */
        @XmlElement(name = "Cd")
        public String code;
    }
}
