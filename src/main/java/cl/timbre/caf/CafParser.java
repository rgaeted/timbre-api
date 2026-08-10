package cl.timbre.caf;

import cl.timbre.xml.XmlUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;

public final class CafParser {

    private CafParser() {}

    public static Caf parse(byte[] xml) {
        Document doc = XmlUtil.parse(xml);
        Element root = doc.getDocumentElement();
        if (!"AUTORIZACION".equals(root.getNodeName())) {
            throw new IllegalArgumentException("El archivo no es un CAF del SII");
        }

        Element caf = XmlUtil.child(root, "CAF");
        Element da = XmlUtil.child(caf, "DA");
        Element rng = XmlUtil.child(da, "RNG");

        return new Caf(
                XmlUtil.text(da, "RE"),
                XmlUtil.text(da, "RS"),
                Integer.parseInt(XmlUtil.text(da, "TD")),
                Integer.parseInt(XmlUtil.text(rng, "D")),
                Integer.parseInt(XmlUtil.text(rng, "H")),
                LocalDate.parse(XmlUtil.text(da, "FA")),
                XmlUtil.serialize(caf),
                XmlUtil.text(root, "RSASK"));
    }

    public static PrivateKey privateKey(Caf caf) {
        return privateKey(caf.privateKeyPem());
    }

    public static PrivateKey privateKey(String privateKeyPem) {
        try {
            byte[] pkcs1 = Base64.getMimeDecoder().decode(stripPem(privateKeyPem));
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return factory.generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1InPkcs8(pkcs1)));
        } catch (Exception e) {
            throw new IllegalArgumentException("La llave privada del CAF no se pudo leer", e);
        }
    }

    private static String stripPem(String pem) {
        return pem.replaceAll("-----(BEGIN|END)[^-]+-----", "").replaceAll("\\s", "");
    }

    /**
     * El SII entrega la llave en PKCS#1 ("BEGIN RSA PRIVATE KEY"), pero el JDK
     * solo sabe leer PKCS#8. Sin BouncyCastle hay que envolverla a mano:
     * SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL }, OCTET STRING pkcs1 }
     */
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] algId = HexFormat.of().parseHex("300d06092a864886f70d0101010500");
        byte[] octetString = derTagged(0x04, pkcs1);

        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(version);
        inner.writeBytes(algId);
        inner.writeBytes(octetString);

        return derTagged(0x30, inner.toByteArray());
    }

    /** Envuelve el contenido en un TLV DER con el tag dado, con longitud en forma larga si hace falta. */
    private static byte[] derTagged(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            byte[] lengthBytes = trimLeadingZeros(new byte[]{
                    (byte) (length >>> 24), (byte) (length >>> 16),
                    (byte) (length >>> 8), (byte) length});
            out.write(0x80 | lengthBytes.length);
            out.writeBytes(lengthBytes);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] trimLeadingZeros(byte[] bytes) {
        int start = 0;
        while (start < bytes.length - 1 && bytes[start] == 0) {
            start++;
        }
        byte[] trimmed = new byte[bytes.length - start];
        System.arraycopy(bytes, start, trimmed, 0, trimmed.length);
        return trimmed;
    }
}
