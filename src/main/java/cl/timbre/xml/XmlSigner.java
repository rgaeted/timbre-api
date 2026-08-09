package cl.timbre.xml;

import cl.timbre.cert.SigningMaterial;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.KeyValue;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import java.util.List;

/**
 * Firma XMLDSig enveloped, con los algoritmos que exige el SII: SHA1 para el
 * digest, RSA-SHA1 para la firma y canonicalización C14N inclusiva. Son
 * algoritmos antiguos, pero el SII no acepta otros.
 */
public final class XmlSigner {

    private XmlSigner() {}

    public static void sign(Document doc, Element parent, String referenceId,
                            SigningMaterial material) {
        try {
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");

            // El SII restringe <Reference><Transforms> a un unico <Transform>
            // (ver xmldsignature_v10.xsd): un segundo Transform de canonicalizacion
            // aqui invalida el documento contra el XSD real. JSR-105 aplica C14N
            // por defecto al calcular el digest cuando el ultimo transform entrega
            // un node-set, asi que igual queda cubierto.
            Reference reference = factory.newReference(
                    "#" + referenceId,
                    factory.newDigestMethod(DigestMethod.SHA1, null),
                    List.of(
                            factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                    null, null);

            SignedInfo signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE,
                            (C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(SignatureMethod.RSA_SHA1, null),
                    List.of(reference));

            KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
            KeyValue keyValue = keyInfoFactory.newKeyValue(material.certificate().getPublicKey());
            X509Data x509Data = keyInfoFactory.newX509Data(List.of(material.certificate()));
            KeyInfo keyInfo = keyInfoFactory.newKeyInfo(List.of(keyValue, x509Data));

            DOMSignContext context = new DOMSignContext(material.privateKey(), parent);
            // El firmante necesita saber que el atributo ID es un identificador,
            // o no resuelve la referencia "#F1042T33".
            markIdAttribute(context, doc, referenceId);

            XMLSignature signature = factory.newXMLSignature(signedInfo, keyInfo);
            signature.sign(context);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el documento", e);
        }
    }

    private static void markIdAttribute(DOMSignContext context, Document doc, String referenceId) {
        var elementos = doc.getElementsByTagName("*");
        for (int i = 0; i < elementos.getLength(); i++) {
            Element element = (Element) elementos.item(i);
            if (referenceId.equals(element.getAttribute("ID"))) {
                context.setIdAttributeNS(element, null, "ID");
                return;
            }
        }
        throw new IllegalStateException("No existe ningun elemento con ID=" + referenceId);
    }
}
