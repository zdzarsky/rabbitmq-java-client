// Copyright (c) 2019 Pivotal Software, Inc.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 1.1 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.client.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Utility to extract information from X509 certificates.
 *
 * @since 4.11.0
 */
public class TlsUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(TlsUtils.class);
    private static final List<String> KEY_USAGE = Collections.unmodifiableList(Arrays.asList(
            "digitalSignature", "nonRepudiation", "keyEncipherment",
            "dataEncipherment", "keyAgreement", "keyCertSign",
            "cRLSign", "encipherOnly", "decipherOnly"
    ));
    private static final Map<String, String> EXTENDED_KEY_USAGE = Collections.unmodifiableMap(new HashMap<String, String>() {{
        put("1.3.6.1.5.5.7.3.1", "TLS Web server authentication");
        put("1.3.6.1.5.5.7.3.2", "TLS Web client authentication");
        put("1.3.6.1.5.5.7.3.3", "Signing of downloadable executable code");
        put("1.3.6.1.5.5.7.3.4", "E-mail protection");
        put("1.3.6.1.5.5.7.3.8", "Binding the hash of an object to a time from an agreed-upon time");
    }});
    private static String PARSING_ERROR = "<parsing-error>";
    private static final Map<String, ExtensionStringConverter> EXTENSIONS = Collections.unmodifiableMap(
            new HashMap<String, ExtensionStringConverter>() {{
                put("2.5.29.14", new ExtensionStringConverter() {
                    @Override
                    public String convert(byte[] derOctetString, X509Certificate certificate) {
                        return "SubjectKeyIdentifier = " + octetStringHexDump(derOctetString);
                    }
                });
                put("2.5.29.15", new ExtensionStringConverter() {
                    @Override
                    public String convert(byte[] derOctetString, X509Certificate certificate) {
                        return "KeyUsage = " + keyUsageBitString(certificate.getKeyUsage(), derOctetString);
                    }
                });
                put("2.5.29.16", new HexDumpConverter("PrivateKeyUsage"));
                put("2.5.29.17", new ExtensionStringConverter() {
                    @Override
                    public String convert(byte[] derOctetString, X509Certificate certificate) {
                        try {
                            return "SubjectAlternativeName = " + sans(certificate, "/");
                        } catch (CertificateParsingException e) {
                            return "SubjectAlternativeName = " + PARSING_ERROR;
                        }
                    }
                });
                put("2.5.29.18", new HexDumpConverter("IssuerAlternativeName"));
                put("2.5.29.19", new ExtensionStringConverter() {
                    @Override
                    public String convert(byte[] derOctetString, X509Certificate certificate) {
                        return "BasicConstraints = " + basicConstraints(derOctetString);
                    }
                });
                put("2.5.29.30", new HexDumpConverter("NameConstraints"));
                put("2.5.29.33", new HexDumpConverter("PolicyMappings"));
                put("2.5.29.35", new ExtensionStringConverter() {
                    @Override
                    public String convert(byte[] derOctetString, X509Certificate certificate) {
                        return "AuthorityKeyIdentifier = " + authorityKeyIdentifier(derOctetString);
                    }
                });
                put("2.5.29.36", new HexDumpConverter("PolicyConstraints"));
                put("2.5.29.37", new ExtensionStringConverter() {
                    @Override
                    public String convert(byte[] derOctetString, X509Certificate certificate) {
                        return "ExtendedKeyUsage = " + extendedKeyUsage(derOctetString, certificate);
                    }
                });
            }});

    /**
     * Log details on peer certificate and certification chain.
     * <p>
     * The log level is debug. Common X509 extensions are displayed in a best-effort
     * fashion, a hexadecimal dump is made for less commonly used extensions.
     *
     * @param session the {@link SSLSession} to extract the certificates from
     */
    public static void logPeerCertificateInfo(SSLSession session) {
        if (LOGGER.isDebugEnabled()) {
            try {
                Certificate[] peerCertificates = session.getPeerCertificates();
                if (peerCertificates != null && peerCertificates.length > 0) {
                    LOGGER.debug(peerCertificateInfo(peerCertificates[0], "Peer's leaf certificate"));
                    for (int i = 1; i < peerCertificates.length; i++) {
                        LOGGER.debug(peerCertificateInfo(peerCertificates[i], "Peer's certificate chain entry"));
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Error while logging peer certificate info: {}", e.getMessage());
            }
        }
    }

    /**
     * Get a string representation of certificate info.
     *
     * @param certificate the certificate to analyze
     * @param prefix      the line prefix
     * @return information about the certificate
     */
    public static String peerCertificateInfo(Certificate certificate, String prefix) {
        X509Certificate c = (X509Certificate) certificate;
        try {
            return String.format("%s subject: %s, subject alternative names: %s, " +
                            "issuer: %s, not valid after: %s, X.509 usage extensions: %s",
                    prefix, c.getSubjectDN().getName(), sans(c, ","), c.getIssuerDN().getName(),
                    c.getNotAfter(), extensions(c));
        } catch (Exception e) {
            return "Error while retrieving " + prefix + " certificate information";
        }
    }

    private static String sans(X509Certificate c, String separator) throws CertificateParsingException {
        Collection<List<?>> sans = c.getSubjectAlternativeNames();
        sans = sans == null ? new ArrayList<List<?>>() : sans;
        return join(separator, new ArrayList<List<?>>(sans));
    }


    /**
     * Human-readable representation of an X509 certificate extension.
     * <p>
     * Common extensions are supported in a best-effort fashion, less commonly
     * used extensions are displayed as an hexadecimal dump.
     * <p>
     * Extensions come encoded as a DER Octet String, which itself can contain
     * other DER-encoded objects, making a comprehensive support in this utility
     * impossible.
     *
     * @param oid            extension OID
     * @param derOctetString the extension value as a DER octet string
     * @param certificate    the certificate
     * @return the OID and the value
     * @see <a href="http://luca.ntop.org/Teaching/Appunti/asn1.html">A Layman's Guide to a Subset of ASN.1, BER, and DER</a>
     * @see <a href="https://docs.microsoft.com/en-us/windows/desktop/seccertenroll/about-der-encoding-of-asn-1-types">DER Encoding of ASN.1 Types</a>
     */
    public static String extensionPrettyPrint(String oid, byte[] derOctetString, X509Certificate certificate) {
        try {
            ExtensionStringConverter converter = EXTENSIONS.get(oid);
            if (converter == null) {
                converter = new HexDumpConverter(oid);
            }
            return converter.convert(derOctetString, certificate);
        } catch (Exception e) {
            return oid + " = " + PARSING_ERROR;
        }
    }

    private static String extensions(X509Certificate certificate) {
        List<String> extensions = new ArrayList<String>();
        for (String oid : certificate.getCriticalExtensionOIDs()) {
            extensions.add(extensionPrettyPrint(oid, certificate.getExtensionValue(oid), certificate) + " (critical)");
        }
        for (String oid : certificate.getNonCriticalExtensionOIDs()) {
            extensions.add(extensionPrettyPrint(oid, certificate.getExtensionValue(oid), certificate) + " (non-critical)");
        }
        return join(", ", extensions);
    }

    private static String octetStringHexDump(byte[] derOctetString) {
        // this is an octet string in a octet string, [4 total_length 4 length ...]
        if (derOctetString.length > 4 && derOctetString[0] == 4 && derOctetString[2] == 4) {
            return hexDump(4, derOctetString);
        } else {
            return hexDump(0, derOctetString);
        }
    }

    private static String hexDump(int start, byte[] derOctetString) {
        List<String> hexs = new ArrayList<String>();
        for (int i = start; i < derOctetString.length; i++) {
            hexs.add(String.format("%02X", derOctetString[i]));
        }
        return join(":", hexs);
    }

    private static String keyUsageBitString(boolean[] keyUsage, byte[] derOctetString) {
        if (keyUsage != null) {
            List<String> usage = new ArrayList<String>();
            for (int i = 0; i < keyUsage.length; i++) {
                if (keyUsage[i]) {
                    usage.add(KEY_USAGE.get(i));
                }
            }
            return join("/", usage);
        } else {
            return hexDump(0, derOctetString);
        }
    }

    private static String basicConstraints(byte[] derOctetString) {
        if (derOctetString.length == 4 && derOctetString[3] == 0) {
            // e.g. 04:02:30:00 [octet_string length sequence size]
            return "CA:FALSE";
        } else if (derOctetString.length >= 7 && derOctetString[2] == 48 && derOctetString[4] == 1) {
            // e.g. 04:05:30:03:01:01:FF [octet_string length sequence boolean length boolean_value]
            return "CA:" + (derOctetString[6] == 0 ? "FALSE" : "TRUE");
        } else {
            return hexDump(0, derOctetString);
        }
    }

    private static String authorityKeyIdentifier(byte[] derOctetString) {
        if (derOctetString.length == 26 && derOctetString[0] == 04) {
            // e.g. 04:18:30:16:80:14:FB:D2:7C:63:DF:7F:D4:A4:8E:9A:20:43:F5:DC:75:6F:B6:D8:51:6F
            // [octet_string length sequence ?? ?? key_length key]
            return "keyid:" + hexDump(6, derOctetString);
        } else {
            return hexDump(0, derOctetString);
        }

    }

    private static String extendedKeyUsage(byte[] derOctetString, X509Certificate certificate) {
        List<String> extendedKeyUsage = null;
        try {
            extendedKeyUsage = certificate.getExtendedKeyUsage();
            if (extendedKeyUsage == null) {
                return hexDump(0, derOctetString);
            } else {
                List<String> extendedKeyUsageLiteral = new ArrayList<String>();
                for (String oid : extendedKeyUsage) {
                    String literal = EXTENDED_KEY_USAGE.get(oid);
                    extendedKeyUsageLiteral.add(literal == null ? oid : literal);
                }
                return join("/", extendedKeyUsageLiteral);
            }
        } catch (CertificateParsingException e) {
            return PARSING_ERROR;
        }
    }

    private static String join(String separator, List<?> items) {
        StringBuilder builder = new StringBuilder();
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    builder.append(separator);
                }
                builder.append(items.get(i));
            }
        }
        return builder.toString();
    }

    private interface ExtensionStringConverter {

        String convert(byte[] derOctetString, X509Certificate certificate);

    }

    private static class HexDumpConverter implements ExtensionStringConverter {

        private final String extension;

        private HexDumpConverter(String extension) {
            this.extension = extension;
        }

        @Override
        public String convert(byte[] derOctetString, X509Certificate certificate) {
            return extension + " = " + hexDump(0, derOctetString);
        }
    }

}
