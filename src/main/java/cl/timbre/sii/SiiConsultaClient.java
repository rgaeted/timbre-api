package cl.timbre.sii;

import cl.timbre.config.SiiProperties;
import cl.timbre.domain.Emisor;
import cl.timbre.rut.RutValidator;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consulta el estado de un envio ya subido al SII por su trackId. Servicio legacy
 * SOAP (QueryEstUp.jws) sin WSDL disponible en este repo -- mismo riesgo que
 * SiiAuthClient: mejor conocimiento disponible, a ajustar contra el SII real.
 */
@Component
public class SiiConsultaClient {

    public enum EstadoSii { ACEPTADO, RECHAZADO, EN_PROCESO }

    public record ResultadoConsulta(EstadoSii estado, String detalle) {}

    private final SiiUrlResolver urlResolver;
    private final RestClient restClient;

    public SiiConsultaClient(SiiUrlResolver urlResolver, SiiProperties properties) {
        this.urlResolver = urlResolver;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeoutMs());
        requestFactory.setReadTimeout(properties.timeoutMs());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public ResultadoConsulta consultar(Emisor emisor, SiiAuthClient.Token token, String trackId) {
        String url = urlResolver.resolve(emisor) + "/DTEWS/QueryEstUp.jws";
        String sobre = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><getEstUp>"
                + "<RutCompania>" + RutValidator.body(emisor.getRut()) + "</RutCompania>"
                + "<DvCompania>" + RutValidator.dv(emisor.getRut()) + "</DvCompania>"
                + "<Token>" + token.valor() + "</Token>"
                + "<TrackId>" + trackId + "</TrackId>"
                + "</getEstUp></soapenv:Body></soapenv:Envelope>";

        String respuesta = restClient.post()
                .uri(url)
                .contentType(MediaType.TEXT_XML)
                .body(sobre)
                .retrieve()
                .body(String.class);

        String codigo = extraerEtiqueta(respuesta, "ESTADO");
        String glosa = extraerEtiquetaOpcional(respuesta, "GLOSA");
        return new ResultadoConsulta(mapearEstado(codigo), glosa);
    }

    /**
     * Sin WSDL del SII, no se conoce el universo completo de codigos de ESTADO.
     * Solo se reconocen explicitamente los que hay razonable confianza en su
     * significado; todo lo demas (incluyendo "aceptado con reparos", para el que
     * no hay codigo identificado) cae en EN_PROCESO, que el job reintenta -- nunca
     * se asume un resultado terminal ante la duda.
     */
    private EstadoSii mapearEstado(String codigo) {
        String normalizado = codigo == null ? "" : codigo.trim().toUpperCase(Locale.ROOT);
        return switch (normalizado) {
            case "EPR", "SOK" -> EstadoSii.ACEPTADO;
            case "RCH", "RCT", "RFR", "RSC" -> EstadoSii.RECHAZADO;
            default -> EstadoSii.EN_PROCESO;
        };
    }

    private String extraerEtiqueta(String xml, String etiqueta) {
        String sinEscapar = xml.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        Matcher matcher = Pattern.compile("<" + etiqueta + ">([^<]*)</" + etiqueta + ">").matcher(sinEscapar);
        if (!matcher.find()) {
            throw new IllegalStateException("La respuesta del SII no trae <" + etiqueta + ">");
        }
        return matcher.group(1);
    }

    private String extraerEtiquetaOpcional(String xml, String etiqueta) {
        try {
            return extraerEtiqueta(xml, etiqueta);
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
