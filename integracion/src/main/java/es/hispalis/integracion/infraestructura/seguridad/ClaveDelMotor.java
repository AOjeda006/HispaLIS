package es.hispalis.integracion.infraestructura.seguridad;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La clave con la que el motor firma su aserción de cliente.
 *
 * <p>Es <strong>asimétrica</strong> y eso no es una preferencia: en SMART Backend Services el
 * laboratorio nunca conoce la clave privada del cliente, solo su parte pública, y por eso no hay
 * ningún secreto compartido que copiar entre despliegues ni que se pueda filtrar desde el lado del
 * servidor.
 *
 * <p><strong>La clave privada llega por variable de entorno</strong>, en PKCS#8 y base64. Si no llega
 * se genera una efímera y el arranque lo dice en voz alta: sirve para levantar la pila de desarrollo
 * de un tirón —el JWKS se publica y Keycloak se la baja— y no sirve para nada más, porque al
 * reiniciar el motor cambia y todos los testigos vivos dejan de poder renovarse.
 *
 * <p>El identificador de clave es su <em>thumbprint</em> RFC 7638, calculado a partir de la propia
 * clave. Así dos claves distintas nunca comparten {@code kid}, que es lo que hace que una rotación
 * pueda solaparse: mientras el JWKS publique las dos, las firmas viejas siguen comprobándose.
 */
public class ClaveDelMotor {

    private static final Logger log = LoggerFactory.getLogger(ClaveDelMotor.class);

    /** RS384: es el algoritmo que la norma exige soportar para las aserciones de cliente. */
    static final JWSAlgorithm ALGORITMO = JWSAlgorithm.RS384;

    private final RSAKey clave;

    public ClaveDelMotor(PropiedadesDeIdentidad propiedades) {
        this.clave = propiedades.hayClavePropia() ? deLaVariableDeEntorno(propiedades.clavePrivada()) : efimera();
    }

    /** La clave completa, con su parte privada. No sale de aquí más que para firmar. */
    RSAKey paraFirmar() {
        return clave;
    }

    /** Lo que se publica: solo la parte pública, en el formato que Keycloak se baja del JWKS. */
    public Map<String, Object> jwksPublico() {
        return new JWKSet(clave.toPublicJWK()).toJSONObject();
    }

    private static RSAKey deLaVariableDeEntorno(String base64) {
        try {
            byte[] pkcs8 = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
            RSAPrivateCrtKey privada =
                    (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));

            // La parte pública se deriva de la privada en vez de configurarse aparte: dos mitades que
            // pueden dejar de casar son dos mitades que algún día no casan, y el fallo aparecería como
            // una firma inválida sin explicación.
            RSAPublicKey publica = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(privada.getModulus(), privada.getPublicExponent()));

            return conThumbprint(new RSAKey.Builder(publica).privateKey(privada));
        } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException fallo) {
            throw new IllegalStateException(
                    "HISPALIS_MOTOR_CLAVE no es una clave RSA privada en PKCS#8 y base64. Genérala con: "
                            + "openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -outform DER | base64 -w0",
                    fallo);
        }
    }

    private static RSAKey efimera() {
        try {
            RSAKey generada = conThumbprint(new RSAKey.Builder(new RSAKeyGenerator(2048).generate()));
            log.warn("⚠️  CLAVE EFÍMERA (falta HISPALIS_MOTOR_CLAVE): el motor ha generado una clave RSA que muere "
                    + "con el proceso. Vale para la pila de desarrollo y para nada más: al reiniciar cambia "
                    + "el `kid` y el servidor de identidad tiene que volver a bajarse el JWKS.");
            return generada;
        } catch (JOSEException fallo) {
            throw new IllegalStateException("No se pudo generar la clave efímera del motor", fallo);
        }
    }

    private static RSAKey conThumbprint(RSAKey.Builder builder) {
        try {
            RSAKey sinIdentificar =
                    builder.keyUse(KeyUse.SIGNATURE).algorithm(ALGORITMO).build();
            return new RSAKey.Builder(sinIdentificar)
                    .keyID(sinIdentificar.computeThumbprint().toString())
                    .build();
        } catch (JOSEException fallo) {
            throw new IllegalStateException("No se pudo calcular el identificador de la clave del motor", fallo);
        }
    }
}
