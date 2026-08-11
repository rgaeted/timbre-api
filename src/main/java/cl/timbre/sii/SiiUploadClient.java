package cl.timbre.sii;

import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Emisor;
import cl.timbre.rut.RutValidator;
import cl.timbre.xml.XmlUtil;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

/**
 * Sube un EnvioDTE ya firmado al endpoint de recepcion del SII (HTTP multipart, no
 * SOAP). Formato del lado del SII sin WSDL disponible -- mejor conocimiento
 * disponible, a ajustar contra el ambiente real.
 */
@Component
public class SiiUploadClient {

    public record ResultadoSubida(boolean exitoso, String trackId, String detalle) {}

    private final SiiUrlResolver urlResolver;
    private final RestClient restClient;

    public SiiUploadClient(SiiUrlResolver urlResolver, SiiProperties properties) {
        this.urlResolver = urlResolver;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeoutMs());
        requestFactory.setReadTimeout(properties.timeoutMs());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public ResultadoSubida subir(Emisor emisor, SiiAuthClient.Token token, String xmlContent, String nombreArchivo) {
        String url = urlResolver.resolve(emisor) + "/cgi_dte/UPL/DTEUpload";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("rutSender", RutValidator.body(emisor.getRutEnvia()));
        body.add("dvSender", RutValidator.dv(emisor.getRutEnvia()));
        body.add("rutCompany", RutValidator.body(emisor.getRut()));
        body.add("dvCompany", RutValidator.dv(emisor.getRut()));
        body.add("archivo", new ByteArrayResource(xmlContent.getBytes(StandardCharsets.ISO_8859_1)) {
            @Override
            public String getFilename() {
                return nombreArchivo;
            }
        });

        String respuesta = restClient.post()
                .uri(url)
                .header("Cookie", "TOKEN=" + token.valor())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);

        return parsearRespuesta(respuesta);
    }

    private ResultadoSubida parsearRespuesta(String xml) {
        var doc = XmlUtil.parse(xml.getBytes(StandardCharsets.ISO_8859_1));
        String status = XmlUtil.text(doc.getDocumentElement(), "STATUS");
        if ("0".equals(status)) {
            String trackId = XmlUtil.text(doc.getDocumentElement(), "TRACKID");
            return new ResultadoSubida(true, trackId, null);
        }
        return new ResultadoSubida(false, null, "El SII rechazo el envio (STATUS=" + status + ")");
    }
}
