package com.sahanswitch.iso20022.infrastructure;

import com.sahanswitch.iso20022.domain.Iso20022ValidationException;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Locale;

/**
 * Safe XML reading and writing for ISO 20022 messages.
 *
 * <p><b>Task 4.</b> The messages arrive from the network, so the parser is hardened against
 * XML attacks:
 * <ul>
 *   <li><b>XXE</b> (XML External Entity): DTDs and external entities are switched off, and
 *       any document containing {@code <!DOCTYPE} is rejected outright. ISO 20022 messages
 *       never need a DTD.</li>
 *   <li><b>Oversized input</b> ("billion laughs", memory exhaustion): input over
 *       {@link #MAX_XML_CHARS} characters is rejected before parsing.</li>
 * </ul>
 */
public final class Iso20022XmlSupport {

    /** A single-transaction pacs message is a few KB; 1 MB is far more than enough. */
    public static final int MAX_XML_CHARS = 1_000_000;

    private Iso20022XmlSupport() {
    }

    /** Creates a (thread-safe, reusable) JAXB context for the given document class. */
    public static JAXBContext newContext(Class<?> documentClass) {
        try {
            return JAXBContext.newInstance(documentClass);
        } catch (JAXBException exception) {
            throw new IllegalStateException(
                    "Cannot create JAXB context for " + documentClass.getSimpleName(), exception);
        }
    }

    /** Serialises a document to indented UTF-8 XML with an XML declaration. */
    public static String marshal(JAXBContext context, Object document) {
        try {
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");

            StringWriter writer = new StringWriter();
            marshaller.marshal(document, writer);
            return writer.toString();
        } catch (JAXBException exception) {
            throw new IllegalStateException("Cannot serialise ISO 20022 message", exception);
        }
    }

    /**
     * Parses XML into the given document class. The root element name and namespace must
     * match the class exactly (so a pacs.002 is not accepted where a pacs.008 is expected).
     *
     * @throws Iso20022ValidationException if the XML is empty, too large, contains a DOCTYPE,
     *                                     is not well-formed, or has the wrong root element
     */
    public static <T> T unmarshal(JAXBContext context, String xml, Class<T> documentClass) {

        if (xml == null || xml.isBlank()) {
            throw new Iso20022ValidationException("ISO 20022 message is empty");
        }

        if (xml.length() > MAX_XML_CHARS) {
            throw new Iso20022ValidationException("ISO 20022 message is too large");
        }

        if (xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) {
            throw new Iso20022ValidationException("DOCTYPE declarations are not allowed in ISO 20022 messages");
        }

        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));

            Object parsed = context.createUnmarshaller().unmarshal(reader);

            if (!documentClass.isInstance(parsed)) {
                throw new Iso20022ValidationException(
                        "Unexpected message type, expected " + documentClass.getSimpleName());
            }

            return documentClass.cast(parsed);

        } catch (JAXBException | XMLStreamException exception) {
            throw new Iso20022ValidationException(
                    "XML is not well-formed or is not the expected ISO 20022 message (check the root "
                            + "element and namespace)");
        }
    }
}
