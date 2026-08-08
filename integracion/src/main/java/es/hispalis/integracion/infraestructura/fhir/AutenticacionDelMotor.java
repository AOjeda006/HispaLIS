package es.hispalis.integracion.infraestructura.fhir;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import es.hispalis.integracion.infraestructura.seguridad.TestigoDeSistema;

/**
 * El punto por donde el motor se identifica ante la API del laboratorio.
 *
 * <p>El motor escribe como un cliente <strong>{@code system/}</strong> vía SMART Backend Services
 * (D5): pide un testigo con su propia identidad y lo pone en cada petición. Es un interceptor del
 * cliente FHIR y no un envoltorio alrededor de las llamadas porque así los canales no se enteran: lo
 * que escriben es FHIR, y quién lo firma es asunto de esta capa.
 */
public interface AutenticacionDelMotor extends IClientInterceptor {

    /**
     * La identificación de verdad: {@code Authorization: Bearer} con el testigo de sistema.
     *
     * <p>Si no hay testigo <strong>la petición sale igual, sin cabecera</strong>, y el laboratorio la
     * rechaza con un {@code 401} que el canal manda a la bandeja de errores. Es deliberado: fallar
     * aquí, en el interceptor, convertiría un servidor de identidad caído en una excepción sin
     * contexto a mitad de un canal, y lo que se quiere es un mensaje reprocesable con su motivo.
     */
    final class PorBackendServices implements AutenticacionDelMotor {

        private final TestigoDeSistema testigos;

        public PorBackendServices(TestigoDeSistema testigos) {
            this.testigos = testigos;
        }

        @Override
        public void interceptRequest(IHttpRequest peticion) {
            testigos.testigo().ifPresent(testigo -> peticion.addHeader("Authorization", "Bearer " + testigo));
        }

        @Override
        public void interceptResponse(IHttpResponse respuesta) {
            // Un testigo puede morir antes de su `exp` —una rotación de claves, una sesión revocada—.
            // Sin esto, el motor seguiría presentando el mismo testigo muerto hasta que venciera su
            // reloj y todo lo de en medio acabaría en la bandeja de errores.
            if (respuesta.getStatus() == 401) {
                testigos.olvidarlo();
            }
        }
    }

    /**
     * El motor va sin credenciales.
     *
     * <p>Solo vale contra un laboratorio que tampoco exige testigo —{@code hispalis.seguridad.habilitada=false},
     * que es como corren los tests y el desarrollo local sin Keycloak—. Apagarlo es una decisión que
     * hay que escribir, y el arranque la avisa.
     */
    final class SinIdentidad implements AutenticacionDelMotor {

        @Override
        public void interceptRequest(IHttpRequest peticion) {
            // Sin cabecera de autorización.
        }

        @Override
        public void interceptResponse(IHttpResponse respuesta) {
            // Nada que renovar: no hay testigo.
        }
    }
}
