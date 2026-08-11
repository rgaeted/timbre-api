package cl.timbre.sii;

import cl.timbre.cert.CertificateProvider;
import cl.timbre.cert.SigningMaterial;
import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Emisor;
import cl.timbre.xml.XmlSigner;
import cl.timbre.xml.XmlUtil;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autenticacion contra el SII: pide una semilla, la firma con el certificado del
 * emisor, y la canjea por un token. Servicios legacy SOAP (CrSeed.jws,
 * GetTokenFromSeed.jws) sin WSDL disponible en este repo -- los sobres XML se arman
 * a mano con el mejor conocimiento disponible, a ajustar contra el SII real.
 */
@Component
public class SiiAuthClient {

    public record Token(String valor) {}

    private final SiiUrlResolver urlResolver;
    private final CertificateProvider certificateProvider;
    private final RestClient restClient;

    public SiiAuthClient(SiiUrlResolver urlResolver, CertificateProvider certificateProvider,
                         SiiProperties properties) {
        this.urlResolver = urlResolver;
        this.certificateProvider = certificateProvider;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeoutMs());
        requestFactory.setReadTimeout(properties.timeoutMs());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public Token obtenerToken(Emisor emisor) {
        String semilla = pedirSemilla(emisor);
        String semillaFirmada = firmarSemilla(semilla, emisor);
        return new Token(canjearToken(emisor, semillaFirmada));
    }

    private String pedirSemilla(Emisor emisor) {
        String url = urlResolver.resolve(emisor) + "/DTEWS/CrSeed.jws";
        String sobre = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><getSeed/></soapenv:Body></soapenv:Envelope>";

        String respuesta = restClient.post()
                .uri(url)
                .contentType(MediaType.TEXT_XML)
                .body(sobre)
                .retrieve()
                .body(String.class);

        return extraerEtiqueta(respuesta, "SEMILLA");
    }

    private String firmarSemilla(String semilla, Emisor emisor) {
        Document doc = XmlUtil.parse(
                ("<getToken><item><Semilla>" + semilla + "</Semilla></item></getToken>")
                        .getBytes(StandardCharsets.UTF_8));
        SigningMaterial material = certificateProvider.forEmisor(emisor);
        XmlSigner.signWholeDocument(doc, doc.getDocumentElement(), material);
        return XmlUtil.serialize(doc.getDocumentElement());
    }

    private String canjearToken(Emisor emisor, String semillaFirmada) {
        String url = urlResolver.resolve(emisor) + "/DTEWS/GetTokenFromSeed.jws";
        String sobre = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><getToken><pszXml><![CDATA[" + semillaFirmada + "]]></pszXml></getToken></soapenv:Body>"
                + "</soapenv:Envelope>";

        String respuesta = restClient.post()
                .uri(url)
                .contentType(MediaType.TEXT_XML)
                .body(sobre)
                .retrieve()
                .body(String.class);

        return extraerEtiqueta(respuesta, "TOKEN");
    }

    /**
     * La respuesta SOAP trae el XML de negocio del SII embebido (a veces escapado)
     * dentro del sobre. En vez de parsear la envoltura SOAP incierta con DOM, se
     * extrae la etiqueta pedida directamente del texto -- mas simple y facil de
     * ajustar cuando se vea una respuesta real del SII.
     */
    private String extraerEtiqueta(String xml, String etiqueta) {
        String sinEscapar = xml.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        Matcher matcher = Pattern.compile("<" + etiqueta + ">([^<]*)</" + etiqueta + ">").matcher(sinEscapar);
        if (!matcher.find()) {
            throw new IllegalStateException("La respuesta del SII no trae <" + etiqueta + ">: " + xml);
        }
        return matcher.group(1);
    }
}
