package es.hispalis.backend;

import es.hispalis.backend.dominio.resultado.UmbralCritico;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import es.hispalis.backend.infraestructura.terminologia.SinServidorDeTerminologia;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * La terminología por defecto de los tests de integración: un catálogo que no declara ningún umbral
 * crítico.
 *
 * <h2>Por qué hace falta</h2>
 *
 * <p>Desde el ítem 46, <strong>validar un resultado exige preguntar si es crítico</strong>: sin esa
 * respuesta no se sabe si basta una firma o hacen falta dos. Y {@link SinServidorDeTerminologia}
 * —que es lo que corre cuando no hay servidor configurado— contesta a esa pregunta lanzando, a
 * propósito: para un umbral, callarse no degrada la respuesta, la invierte.
 *
 * <p>Consecuencia: un laboratorio sin terminología <strong>no puede validar</strong>, que es la
 * decisión correcta y la que se comprueba en {@code DobleValidacionTest}. Pero convertiría en
 * {@code 503} todos los tests que validan de paso para llegar a lo que de verdad prueban — un
 * informe, el bus, el reconciliador, la seguridad—. Este doble es lo que les devuelve el escenario
 * que tenían.
 *
 * <h2>Por qué no es una lista paralela</h2>
 *
 * <p>Porque no lleva ni un código dentro. Lo que dice es «este catálogo no declara umbrales
 * críticos», que es un estado real y el más común: la mayoría de las pruebas del catálogo de la guía
 * no tienen ninguno. Que la respuesta salga de verdad de un {@code $lookup} lo prueba
 * {@code TerminologiaEnLaProyeccionTest} contra un HAPI real, y qué hace el dominio cuando el umbral
 * existe lo prueba {@code DobleValidacionTest}.
 *
 * <h2>Cómo se desactiva</h2>
 *
 * <p>Con {@code hispalis.test.terminologia} a cualquier cosa que no sea {@code doble}. Lo hacen las
 * clases que traen la suya: dos beans {@code @Primary} del mismo tipo no conviven, y la alternativa
 * —dejar que gane el último registrado— depende de un orden que nadie declara.
 */
@TestConfiguration
public class TerminologiaDeLosTests {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "hispalis.test.terminologia", havingValue = "doble", matchIfMissing = true)
    Terminologia terminologiaSinUmbrales() {
        return new SinServidorDeTerminologia() {

            @Override
            public Optional<UmbralCritico> umbralDe(String codigoDePrueba) {
                return Optional.empty();
            }
        };
    }
}
