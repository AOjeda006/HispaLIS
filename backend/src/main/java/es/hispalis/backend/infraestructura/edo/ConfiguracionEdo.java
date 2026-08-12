package es.hispalis.backend.infraestructura.edo;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.hispalis.backend.aplicacion.edo.AbrirNotificacionEdo;
import es.hispalis.backend.aplicacion.edo.DestinatarioDeLaDeclaracion;
import es.hispalis.backend.aplicacion.edo.EnviarNotificacionEdo;
import es.hispalis.backend.aplicacion.exportacion.ApuntarEnLaCohorte;
import es.hispalis.backend.dominio.edo.CatalogoEdo;
import es.hispalis.backend.dominio.edo.RepositorioDeNotificacionesEdo;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.fhir.edo.TraductorDeNotificacionEdo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * El notificador EDO y su cliente, detrás de {@code hispalis.edo.habilitado}.
 *
 * <p>Encendido por defecto, como el bus y las notificaciones: apagar el cumplimiento de una obligación
 * legal tiene que ser una decisión escrita en la configuración, no un valor que falta.
 *
 * <p>Con el notificador apagado, la detección del ítem 47 <strong>sigue funcionando</strong>: el hecho
 * {@code RESULTADO_DECLARABLE} se apunta igual. Lo que no pasa es que nadie lo recoja. Es la misma
 * separación que en las notificaciones —anotar siempre, salir es lo que se apaga— y por la misma
 * razón: apagar la salida no puede cambiar lo que el laboratorio considera declarable.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PropiedadesDelSvea.class)
@ConditionalOnProperty(prefix = "hispalis.edo", name = "habilitado", havingValue = "true", matchIfMissing = true)
class ConfiguracionEdo {

    @Bean
    DestinatarioDeLaDeclaracion destinatarioDeLaDeclaracion(DaoRegistry daos, PropiedadesDelSvea propiedades) {
        return new DestinatarioDeLaDeclaracion(daos, propiedades.destinatario());
    }

    @Bean
    AbrirNotificacionEdo abrirNotificacionEdo(
            RepositorioDeResultados resultados,
            RepositorioDeNotificacionesEdo declaraciones,
            CatalogoEdo catalogo,
            TraductorDeNotificacionEdo traductor,
            DestinatarioDeLaDeclaracion destinatario,
            ApuntarEnLaCohorte cohorte,
            DaoRegistry daos) {
        return new AbrirNotificacionEdo(resultados, declaraciones, catalogo, traductor, destinatario, cohorte, daos);
    }

    @Bean
    EnviarNotificacionEdo enviarNotificacionEdo(
            RepositorioDeNotificacionesEdo declaraciones,
            SaludPublicaHttp saludPublica,
            TraductorDeNotificacionEdo traductor,
            DestinatarioDeLaDeclaracion destinatario,
            DaoRegistry daos) {
        return new EnviarNotificacionEdo(declaraciones, saludPublica, traductor, destinatario, daos);
    }

    @Bean
    HechosDeclarables hechosDeclarables(NamedParameterJdbcTemplate jdbc) {
        return new HechosDeclarables(jdbc);
    }

    @Bean
    SaludPublicaHttp saludPublicaHttp(
            FhirContext contexto,
            ObjectMapper json,
            TraductorDeNotificacionEdo traductor,
            DestinatarioDeLaDeclaracion destinatario,
            PropiedadesDelSvea propiedades) {
        return new SaludPublicaHttp(contexto, json, traductor, destinatario, propiedades);
    }

    @Bean
    NotificadorEdo notificadorEdo(
            HechosDeclarables hechos,
            AbrirNotificacionEdo abrir,
            EnviarNotificacionEdo enviar,
            RepositorioDeNotificacionesEdo declaraciones,
            PropiedadesDelSvea propiedades) {
        return new NotificadorEdo(hechos, abrir, enviar, declaraciones, propiedades);
    }
}
