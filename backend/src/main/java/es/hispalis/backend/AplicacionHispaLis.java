package es.hispalis.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de arranque del backend de HispaLIS.
 *
 * <p>El backend es el Sistema de Información de Laboratorio: mantiene el núcleo de dominio como
 * fuente de verdad y publica una API FHIR R5 cuya proyección se escribe en la misma transacción que
 * el dominio (D3, §9 del diseño).
 */
@SpringBootApplication
public class AplicacionHispaLis {

    public static void main(String[] args) {
        SpringApplication.run(AplicacionHispaLis.class, args);
    }
}
