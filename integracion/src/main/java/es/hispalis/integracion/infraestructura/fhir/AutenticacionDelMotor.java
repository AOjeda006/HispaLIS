package es.hispalis.integracion.infraestructura.fhir;

import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;

/**
 * El punto por donde el motor se identificará ante la API del laboratorio.
 *
 * <p>Es una interfaz y no un hueco vacío. Cuando llegue Keycloak, el motor se autenticará como
 * cliente <strong>{@code system/}</strong> vía SMART Backend Services (D5): pedirá un testigo con su
 * propia identidad y lo pondrá en cada petición. Ese día se añade una implementación que hable con
 * Keycloak y se cambia el bean; ni el cliente FHIR ni los canales se enteran.
 *
 * <p>Existe ya, con la implementación que no autentica, porque el sitio donde encaja la autenticación
 * es una decisión de diseño y no un detalle pendiente: si el cliente FHIR se construyera sin
 * interceptor, añadirlo después obligaría a tocar el cliente, sus pruebas y el cableado.
 */
public interface AutenticacionDelMotor extends IClientInterceptor {

    /**
     * Mientras no hay servidor de identidad, el motor va sin credenciales.
     *
     * <p>Es honesto y visible: el laboratorio todavía no exige testigo a nadie, y una implementación
     * que fingiera autenticar sería peor que ninguna.
     */
    final class SinIdentidadTodavia implements AutenticacionDelMotor {

        @Override
        public void interceptRequest(IHttpRequest peticion) {
            // Sin cabecera de autorización: el laboratorio aún no la pide.
        }

        @Override
        public void interceptResponse(IHttpResponse respuesta) {
            // Nada que renovar mientras no haya testigo.
        }
    }
}
