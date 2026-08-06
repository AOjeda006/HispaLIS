package es.hispalis.integracion.mllp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del listener MLLP.
 *
 * <p>El TLS viene <strong>encendido por defecto</strong> y sin valor por defecto para el almacén de
 * claves: si no se configura, el motor no arranca. Es deliberado. Un plano de sistemas que se levanta
 * en claro «porque todavía no hemos puesto los certificados» es un plano que se queda así, y por ahí
 * viaja el mensaje con el nombre, la fecha de nacimiento y el DNI del paciente en texto plano (D4).
 *
 * @param puerto donde escucha el canal entrante
 * @param tls el material criptográfico del servidor
 */
@ConfigurationProperties("hispalis.mllp")
public record PropiedadesMllp(@DefaultValue("2575") int puerto, Tls tls) {

    /**
     * @param habilitado apagarlo es solo para pruebas locales, y el arranque lo avisa
     * @param almacenDeClaves ruta al almacén con la clave y el certificado del servidor
     * @param clave contraseña del almacén; llega por variable de entorno, nunca en el repositorio
     * @param tipo formato del almacén ({@code PKCS12})
     */
    public record Tls(
            @DefaultValue("true") boolean habilitado,
            String almacenDeClaves,
            String clave,
            @DefaultValue("PKCS12") String tipo) {}
}
