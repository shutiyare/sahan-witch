package com.sahanswitch.iso20022.application;

/**
 * Shared test data: a valid pacs.008 message and helpers to break it in a controlled way.
 * The placeholders let each test change exactly one thing.
 */
public final class Iso20022Fixtures {

    public static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10";

    public static final String VALID_UETR = "8a562c67-ca16-48ba-b074-65581be6f011";

    private Iso20022Fixtures() {
    }

    /** A complete, valid message from SENDERBANK to DESTWALLET. */
    public static String validPacs008() {
        return pacs008(
                "<MsgId>MSG-0001</MsgId>",
                "<NbOfTxs>1</NbOfTxs>",
                "<PmtId><InstrId>INSTR-1</InstrId><EndToEndId>E2E-0001</EndToEndId><TxId>TX-1</TxId>"
                        + "<UETR>" + VALID_UETR + "</UETR></PmtId>",
                "<IntrBkSttlmAmt Ccy=\"USD\">100.50</IntrBkSttlmAmt>",
                "<Dbtr><Nm>Alice Debtor</Nm></Dbtr>",
                "<DbtrAcct><Id><Othr><Id>ACC-111</Id></Othr></Id></DbtrAcct>",
                agent("DbtrAgt", "SENDERBANK"),
                agent("CdtrAgt", "DESTWALLET"),
                "<Cdtr><Nm>Bob Creditor</Nm></Cdtr>",
                "<CdtrAcct><Id><Othr><Id>ACC-222</Id></Othr></Id></CdtrAcct>"
        );
    }

    public static String agent(String element, String memberId) {
        return "<" + element + "><FinInstnId><ClrSysMmbId><MmbId>" + memberId
                + "</MmbId></ClrSysMmbId></FinInstnId></" + element + ">";
    }

    /** Builds a message from its parts; pass an empty string to omit a part. */
    public static String pacs008(
            String msgId, String nbOfTxs, String pmtId, String amount, String debtor, String debtorAccount,
            String debtorAgent, String creditorAgent, String creditor, String creditorAccount
    ) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Document xmlns=\"" + NAMESPACE + "\">"
                + "<FIToFICstmrCdtTrf>"
                + "<GrpHdr>" + msgId + "<CreDtTm>2026-10-05T07:00:00.000Z</CreDtTm>" + nbOfTxs
                + "<SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf></GrpHdr>"
                + "<CdtTrfTxInf>" + pmtId + amount + "<ChrgBr>SLEV</ChrgBr>"
                + debtor + debtorAccount + debtorAgent + creditorAgent + creditor + creditorAccount
                + "</CdtTrfTxInf>"
                + "</FIToFICstmrCdtTrf></Document>";
    }
}
