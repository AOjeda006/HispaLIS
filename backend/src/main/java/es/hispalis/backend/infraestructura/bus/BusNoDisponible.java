package es.hispalis.backend.infraestructura.bus;

/**
 * El bus no está. No es un error de la aplicación: es el caso para el que existe el {@code outbox}.
 *
 * <p>Nunca sale de este paquete ni llega a una respuesta HTTP. Que el broker o el registro estén
 * caídos <strong>no puede</strong> hacer fallar una escritura FHIR: el hecho ya está apuntado en la
 * misma transacción que el dominio, y el relay lo sacará cuando el bus vuelva. Si un {@code POST}
 * fallara por esto, el outbox no estaría haciendo su trabajo — que es exactamente para lo que está.
 */
class BusNoDisponible extends RuntimeException {

    BusNoDisponible(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
