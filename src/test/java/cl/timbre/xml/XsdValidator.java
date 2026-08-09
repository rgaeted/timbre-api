package cl.timbre.xml;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Valida XML contra los XSD oficiales del SII y devuelve los errores encontrados. */
public final class XsdValidator {

    private XsdValidator() {}

    public static List<String> errores(byte[] xml, String xsdPath) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Validator validator = factory.newSchema(new File(xsdPath)).newValidator();

        List<String> errores = new ArrayList<>();
        validator.setErrorHandler(new ErrorHandler() {
            public void warning(SAXParseException e) { }
            public void error(SAXParseException e) { errores.add(e.getMessage()); }
            public void fatalError(SAXParseException e) { errores.add(e.getMessage()); }
        });

        validator.validate(new StreamSource(new ByteArrayInputStream(xml)));
        return errores;
    }
}
