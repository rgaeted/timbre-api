package cl.timbre.cert;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/** Material criptográfico para firmar. Nunca se serializa ni se loguea. */
public record SigningMaterial(PrivateKey privateKey, X509Certificate certificate) {}
