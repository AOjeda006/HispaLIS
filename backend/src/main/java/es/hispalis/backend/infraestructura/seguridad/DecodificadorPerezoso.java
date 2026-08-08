package es.hispalis.backend.infraestructura.seguridad;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * El validador de testigos, construido en la primera petición y no al arrancar.
 *
 * <p>Spring Security ofrece {@code NimbusJwtDecoder.withIssuerLocation(...)}, que lee el
 * descubrimiento al crear el <em>bean</em>. Aquí no vale: el laboratorio y el servidor de identidad
 * levantan a la vez, y con esa forma el arranque del laboratorio dependería de haber ganado la
 * carrera. Lo que se pierde por no tener identidad disponible es poder autenticar a nadie —{@code
 * 401} a todo, que es lo correcto—, no la existencia del proceso.
 *
 * <p>Las tres comprobaciones que se añaden a la firma son las tres que la norma llama obligatorias:
 * que no haya caducado, que lo firme el emisor que este laboratorio reconoce, y que el {@code aud}
 * apunte <strong>a este</strong> servidor. La tercera es la que evita que un testigo perfectamente
 * legítimo, emitido para otro servidor de recursos del mismo <em>realm</em>, valga aquí.
 */
class DecodificadorPerezoso implements JwtDecoder {

    private final DescubrimientoOidc descubrimiento;
    private final PropiedadesDeSeguridad propiedades;
    private final AtomicReference<JwtDecoder> construido = new AtomicReference<>();

    DecodificadorPerezoso(DescubrimientoOidc descubrimiento, PropiedadesDeSeguridad propiedades) {
        this.descubrimiento = descubrimiento;
        this.propiedades = propiedades;
    }

    @Override
    public Jwt decode(String testigo) {
        return real().decode(testigo);
    }

    private JwtDecoder real() {
        JwtDecoder yaHecho = construido.get();
        if (yaHecho != null) {
            return yaHecho;
        }
        DescubrimientoOidc.Documento documento = descubrimiento
                .documento()
                .orElseThrow(
                        () -> new JwtException("No se ha podido leer el descubrimiento del servidor de identidad en "
                                + propiedades.emisor() + ": no hay con qué comprobar la firma."));

        NimbusJwtDecoder nimbus =
                NimbusJwtDecoder.withJwkSetUri(documento.jwks()).build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new JwtIssuerValidator(documento.emisor()), validadorDeAudiencia()));

        // `compareAndSet` y no `set`: dos peticiones simultáneas pueden construirlo a la vez, y lo
        // que no puede pasar es que la segunda tire el decodificador que la primera está usando —
        // con él se va su caché de claves, y la siguiente petición vuelve a ir al JWKS.
        construido.compareAndSet(null, nimbus);
        return construido.get();
    }

    private JwtClaimValidator<Object> validadorDeAudiencia() {
        List<String> aceptadas = propiedades.audiencias();
        return new JwtClaimValidator<>(JwtClaimNames.AUD, declarada -> switch (declarada) {
            case String unica -> aceptadas.contains(unica);
            case List<?> varias -> varias.stream().map(String::valueOf).anyMatch(aceptadas::contains);
            case null, default -> false;
        });
    }
}
