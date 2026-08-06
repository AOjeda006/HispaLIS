package es.hispalis.integracion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * El motor de integración: canales HL7 V2.5.1 sobre MLLP hacia la API FHIR del laboratorio.
 *
 * <p><strong>No es Mirth</strong> (D11, ADR-0005). Los canales son código: se escriben, se revisan y
 * se despliegan por el mismo circuito que el resto del sistema. No hay consola donde editar un
 * canal en caliente, y esa ausencia es la decisión, no una carencia — un mapeo que se puede cambiar
 * sin dejar rastro no se puede auditar, y auditar el mapeo es justamente para lo que existe esto.
 *
 * <p>Los dos planos no se mezclan (D4): por aquí entra <strong>solo</strong> HL7 V2.5.1 y sale
 * <strong>solo</strong> FHIR R5, contra la API pública del laboratorio y como un cliente más (D5).
 * Este servicio no conoce la base de datos del backend ni sus comandos de dominio.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MotorDeIntegracion {

    public static void main(String[] argumentos) {
        SpringApplication.run(MotorDeIntegracion.class, argumentos);
    }
}
