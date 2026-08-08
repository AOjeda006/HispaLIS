package es.hispalis.backend.infraestructura.seguridad;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.consent.ConsentInterceptor;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import es.hispalis.backend.fhir.ConfiguracionServidorFhir;
import es.hispalis.backend.fhir.seguridad.AutorizacionSmart;
import es.hispalis.backend.fhir.seguridad.ConsentimientoDelPaciente;
import es.hispalis.backend.fhir.seguridad.DondeSeAutoriza;
import es.hispalis.backend.fhir.seguridad.QuienLlama;
import es.hispalis.backend.fhir.seguridad.Testigo;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * El cableado de la seguridad: quién puede entrar y con qué.
 *
 * <p>Dos capas, y no son intercambiables:
 *
 * <ol>
 *   <li><strong>Spring Security</strong> comprueba que el testigo existe, está firmado por el emisor
 *       configurado, no ha caducado y va dirigido <em>a este</em> servidor de recursos. Es
 *       criptografía y protocolo: no se escribe a mano.
 *   <li><strong>Los interceptores de HAPI</strong> deciden qué puede hacer y sobre qué datos. Es
 *       FHIR: el filtro HTTP no sabe qué es un compartimento ni de quién es una {@code Observation}.
 * </ol>
 *
 * <p>El interruptor {@code hispalis.seguridad.habilitada} va <strong>encendido por defecto</strong>.
 * Apagarlo deja la API abierta de par en par, así que el arranque lo dice con un aviso que no se
 * puede confundir con ruido — misma regla que el TLS del motor de integración.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesDeSeguridad.class)
public class ConfiguracionDeSeguridad {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionDeSeguridad.class);

    /** El documento de descubrimiento de SMART, que es público por definición. */
    public static final String RUTA_DESCUBRIMIENTO =
            ConfiguracionServidorFhir.RUTA_BASE + "/.well-known/smart-configuration";

    private static final String RUTA_METADATA = ConfiguracionServidorFhir.RUTA_BASE + "/metadata";

    /**
     * Los emparejadores de ruta, <strong>sobre la URL y no sobre Spring MVC</strong>.
     *
     * <p>Con {@code spring-webmvc} en el <em>classpath</em>, {@code securityMatcher("/fhir/**")}
     * construye un {@code MvcRequestMatcher}, que resuelve la ruta preguntándole al
     * {@code DispatcherServlet}. La API FHIR <strong>no la sirve el {@code DispatcherServlet}</strong>
     * sino el servlet de HAPI, así que ese emparejador no casa nunca: la cadena de seguridad se
     * construye, el arranque la anuncia en el log —{@code Will secure Or [Mvc [pattern='/fhir/**']]}—
     * y ninguna petición pasa por ella. <strong>La API queda abierta sin un solo error.</strong>
     *
     * <p>Por eso las rutas se emparejan aquí sobre la URL de la petición. Es la trampa más cara de
     * esta configuración, porque el modo de fallar es «todo verde y la puerta abierta»: hay un test
     * que pide sin testigo y exige un {@code 401}, y es el que la vigila.
     */
    private static final PathPatternRequestMatcher.Builder RUTAS = PathPatternRequestMatcher.withDefaults();

    @Bean
    public DescubrimientoOidc descubrimientoOidc(PropiedadesDeSeguridad propiedades) {
        return new DescubrimientoOidc(propiedades);
    }

    /**
     * Lo que el {@code CapabilityStatement} declara sobre la autorización.
     *
     * <p>Con la seguridad apagada devuelve vacío y la declaración de conformidad no menciona SMART:
     * anunciarlo en un servidor abierto sería mentir en el único documento que un cliente lee para
     * saber si puede confiar.
     */
    @Bean
    public DondeSeAutoriza dondeSeAutoriza(DescubrimientoOidc oidc, PropiedadesDeSeguridad propiedades) {
        return () -> propiedades.habilitada()
                ? oidc.documento()
                        .map(identidad ->
                                new DondeSeAutoriza.Direcciones(identidad.autorizacion(), identidad.testigo()))
                : Optional.empty();
    }

    /**
     * El documento de descubrimiento, colgado de la base FHIR como manda SMART.
     *
     * <p>La regla de servlet es <strong>exacta</strong> y por eso gana a la de prefijo
     * {@code /fhir/*} con la que se publica el servidor de HAPI. Es la especificación de servlets, no
     * un truco: la coincidencia exacta tiene prioridad sobre la de camino.
     */
    @Bean
    public ServletRegistrationBean<DescubrimientoSmart> registroDelDescubrimientoSmart(DescubrimientoOidc oidc) {
        ServletRegistrationBean<DescubrimientoSmart> registro =
                new ServletRegistrationBean<>(new DescubrimientoSmart(oidc), RUTA_DESCUBRIMIENTO);
        registro.setName("descubrimiento-smart");
        return registro;
    }

    @Bean
    public JwtDecoder decodificadorDeTestigos(DescubrimientoOidc descubrimiento, PropiedadesDeSeguridad propiedades) {
        return new DecodificadorPerezoso(descubrimiento, propiedades);
    }

    /**
     * De dónde salen los datos del testigo para el resto del borde.
     *
     * <p>Se leen del contexto de seguridad, que Spring rellena en el mismo hilo que atiende la
     * petición — el mismo en el que corren los interceptores de HAPI.
     */
    @Bean
    public QuienLlama quienLlama() {
        return () -> Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(autenticacion -> ((JwtAuthenticationToken) autenticacion).getToken())
                .map(jwt -> Testigo.de(
                        scopeDe(jwt.getClaims()),
                        jwt.getClaimAsString("patient"),
                        jwt.getClaimAsString("fhirUser"),
                        jwt.getSubject()));
    }

    @Bean
    public SecurityFilterChain cadenaDeLaApiFhir(
            HttpSecurity http, PropiedadesDeSeguridad propiedades, FhirContext contexto) throws Exception {
        http.securityMatcher(RUTAS.matcher(ConfiguracionServidorFhir.RUTA_BASE + "/**"))
                // No hay sesión ni formularios: cada petición trae su testigo y se juzga sola. Sin
                // sesión no hay nada que fijar ni que falsificar, así que CSRF deja de aplicar.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(login -> login.disable());

        if (!propiedades.habilitada()) {
            log.warn("⚠️  SEGURIDAD DESACTIVADA (hispalis.seguridad.habilitada=false): la API FHIR responde a "
                    + "cualquiera, sin testigo. Solo vale para desarrollo local y para los tests.");
            return http.authorizeHttpRequests(rutas -> rutas.anyRequest().permitAll())
                    .build();
        }

        if (!propiedades.hayEmisor()) {
            throw new IllegalStateException(
                    "La seguridad está activada pero no hay emisor: configura hispalis.seguridad.emisor "
                            + "(HISPALIS_OIDC_EMISOR) con la URL del realm, o apágala a conciencia con "
                            + "hispalis.seguridad.habilitada=false.");
        }
        if (propiedades.audiencias().isEmpty()) {
            // Sin `aud` un testigo emitido para OTRO servidor de recursos, con la misma firma y el
            // mismo emisor, valdría aquí. La norma lo llama obligatorio y esto lo trata como tal.
            throw new IllegalStateException("La seguridad está activada pero no hay audiencias: configura "
                    + "hispalis.seguridad.audiencias (HISPALIS_OIDC_AUDIENCIAS) con las bases FHIR por "
                    + "las que se llega a este servidor.");
        }

        RespuestasDeSeguridad respuestas = new RespuestasDeSeguridad(contexto);
        return http.authorizeHttpRequests(
                        rutas -> rutas.requestMatchers(RUTAS.matcher(RUTA_METADATA), RUTAS.matcher(RUTA_DESCUBRIMIENTO))
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {})
                        .authenticationEntryPoint(respuestas)
                        .accessDeniedHandler(respuestas))
                .build();
    }

    /**
     * Registra en el servidor FHIR los dos interceptores que aplican la autorización y el
     * consentimiento.
     *
     * <p>Va como {@link SmartInitializingSingleton} y no como parámetro de la fábrica del servidor
     * para no atar el cableado de FHIR al de la seguridad: el borde FHIR sigue construyéndose igual
     * exista o no este {@code @Configuration}, y quien tiene que saber de los dos mundos es este
     * fichero, que es el de la seguridad.
     */
    @Bean
    public SmartInitializingSingleton aplicarSmartEnElServidorFhir(
            RestfulServer servidor,
            PropiedadesDeSeguridad propiedades,
            QuienLlama quienLlama,
            FhirContext contexto,
            ISearchParamRegistry parametrosDeBusqueda) {
        return () -> {
            if (!propiedades.habilitada()) {
                return;
            }
            servidor.registerInterceptor(new AutorizacionSmart(quienLlama));
            servidor.registerInterceptor(
                    new ConsentInterceptor(new ConsentimientoDelPaciente(quienLlama, contexto, parametrosDeBusqueda)));
        };
    }

    /**
     * El {@code scope} del testigo, venga como cadena o como lista.
     *
     * <p>OAuth2 lo define como cadena separada por espacios y así lo emite Keycloak, pero hay
     * servidores que lo mandan como {@code scp} en forma de lista. Leer las dos formas cuesta cuatro
     * líneas y es lo que hace que cambiar de servidor de identidad no sea tocar este fichero.
     */
    private static String scopeDe(java.util.Map<String, Object> claims) {
        Object scope = claims.get("scope");
        if (scope == null) {
            scope = claims.get("scp");
        }
        if (scope instanceof String texto) {
            return texto;
        }
        if (scope instanceof List<?> lista) {
            return lista.stream()
                    .map(String::valueOf)
                    .reduce((a, b) -> a + " " + b)
                    .orElse("");
        }
        return null;
    }
}
