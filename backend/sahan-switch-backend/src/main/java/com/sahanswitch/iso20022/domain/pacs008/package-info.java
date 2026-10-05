/**
 * JAXB binding for ISO 20022 {@code pacs.008.001.10} (FIToFICustomerCreditTransfer).
 *
 * <p>The namespace declared here is applied to every element of the classes in this package,
 * and is also made the default namespace when marshalling (so no {@code ns2:} prefixes).
 */
@XmlSchema(
        namespace = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10",
        elementFormDefault = XmlNsForm.QUALIFIED,
        xmlns = @XmlNs(prefix = "", namespaceURI = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10")
)
package com.sahanswitch.iso20022.domain.pacs008;

import jakarta.xml.bind.annotation.XmlNs;
import jakarta.xml.bind.annotation.XmlNsForm;
import jakarta.xml.bind.annotation.XmlSchema;
