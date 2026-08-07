package es.hispalis.backend.infraestructura.bus;

import es.hispalis.lab.v1.HechoDeEspecimen;
import es.hispalis.lab.v1.HechoDeInforme;
import es.hispalis.lab.v1.HechoDePeticion;
import es.hispalis.lab.v1.HechoDeResultado;
import es.hispalis.lab.v1.TipoDeHechoDeEspecimen;
import es.hispalis.lab.v1.TipoDeHechoDeInforme;
import es.hispalis.lab.v1.TipoDeHechoDePeticion;
import es.hispalis.lab.v1.TipoDeHechoDeResultado;
import org.apache.avro.specific.SpecificRecord;

/**
 * De la fila del {@code outbox} al mensaje que viaja por el bus.
 *
 * <p>Aquí está el borde entre el mapa de referencias que guarda el laboratorio y el <strong>tipo
 * compilado</strong> que ven los consumidores. Volcar el mapa tal cual —un {@code Map<String,Object>}
 * dentro de un mensaje— sería tener un tópico sin contrato: el consumidor no sabría qué campos puede
 * esperar, el registro no podría decir si un cambio rompe a alguien, y la primera clave que alguien
 * escribiera mal se descubriría en producción.
 *
 * <p>Que este traductor sea explícito tiene un efecto buscado: publicar un dato nuevo obliga a
 * tocar el {@code .avsc}, y tocarlo obliga a pasar por la compatibilidad del registro. Ese es el
 * sitio donde se debe pensar si algo puede salir al bus — no en el caso de uso que apunta el hecho.
 */
final class TraductorAlBus {

    private TraductorAlBus() {}

    static SpecificRecord de(HechoPendiente hecho, Topico topico) {
        return switch (topico) {
            case PETICIONES ->
                HechoDePeticion.newBuilder()
                        .setHechoId(hecho.id().toString())
                        .setTipo(TipoDeHechoDePeticion.valueOf(hecho.tipo().name()))
                        .setOcurridoEn(hecho.creadoEn())
                        .setPacienteId(hecho.claveDeParticion().toString())
                        .setServiceRequestRef(hecho.referenciaObligatoria("serviceRequestRef"))
                        .build();

            case ESPECIMENES ->
                HechoDeEspecimen.newBuilder()
                        .setHechoId(hecho.id().toString())
                        .setTipo(TipoDeHechoDeEspecimen.valueOf(hecho.tipo().name()))
                        .setOcurridoEn(hecho.creadoEn())
                        .setPacienteId(hecho.claveDeParticion().toString())
                        .setSpecimenRef(hecho.referenciaObligatoria("specimenRef"))
                        .build();

            case RESULTADOS ->
                HechoDeResultado.newBuilder()
                        .setHechoId(hecho.id().toString())
                        .setTipo(TipoDeHechoDeResultado.valueOf(hecho.tipo().name()))
                        .setOcurridoEn(hecho.creadoEn())
                        .setPacienteId(hecho.claveDeParticion().toString())
                        .setObservationRef(hecho.referenciaObligatoria("observationRef"))
                        .setSpecimenRef(hecho.referenciaOpcional("specimenRef"))
                        .setServiceRequestRef(hecho.referenciaOpcional("serviceRequestRef"))
                        .setProvenanceRef(hecho.referenciaOpcional("provenanceRef"))
                        .build();

            case INFORMES ->
                HechoDeInforme.newBuilder()
                        .setHechoId(hecho.id().toString())
                        .setTipo(TipoDeHechoDeInforme.valueOf(hecho.tipo().name()))
                        .setOcurridoEn(hecho.creadoEn())
                        .setPacienteId(hecho.claveDeParticion().toString())
                        .setDiagnosticReportRef(hecho.referenciaObligatoria("diagnosticReportRef"))
                        .build();
        };
    }
}
