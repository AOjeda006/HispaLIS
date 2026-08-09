package es.hispalis.backend.fhir;

import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;

/**
 * Una puerta de escritura que el proveedor hereda del framework y que este laboratorio no abre.
 *
 * <p>{@code BaseJpaResourceProvider} trae siete verbos de escritura y los proveedores propios solo
 * sustituyen dos: {@code create} y {@code update}. Los otros cinco —{@code patch}, {@code delete},
 * {@code metaAdd}, {@code metaDelete} y {@code expunge}— más los dos que HAPI 8 añadió después
 * —{@code $merge} y {@code $undo-merge}— escribían la proyección FHIR <strong>dejando el dominio
 * atrás</strong>, que es el fallo entero de {@code ADR-0014}.
 *
 * <p><strong>Se rechazan, no se implementan.</strong> Es la regla 2 de ese ADR: lo que no tiene
 * reglas de negocio definidas se rechaza con un error explícito, en vez de dejar que lo heredado
 * escriba a medias. Un {@code PATCH} que cambie el sexo de un paciente no es un caso de uso que
 * nadie haya pedido; implementarlo «por si acaso» sería inventar semántica de dominio para poder
 * decir que un verbo está soportado.
 *
 * <p>Y se rechazan <strong>aunque hoy HAPI ya los rechace</strong>. Medido contra 8.10.1 con el
 * {@code JpaStorageSettings} de este proyecto: {@code patch}, {@code delete}, {@code metaAdd} y
 * {@code metaDelete} llegaban al fondo y contestaban {@code 200}; {@code expunge}, {@code $merge} y
 * {@code $undo-merge} no, porque {@code expungeEnabled} viene apagado y la operación de fusión
 * necesita un servicio que no está. Pero esas tres negativas <strong>no son nuestras</strong>: son
 * un ajuste con valor por defecto y un <em>bean</em> ausente, y las dos cosas se cambian sin que
 * nadie las relacione con este invariante. Una puerta que está cerrada porque sí no está cerrada.
 *
 * <p>Se traduce a {@code 422}: el recurso está bien formado y lo que no procede es la acción.
 */
public class PuertaHeredadaCerrada extends ReglaDeNegocioIncumplida {

    /**
     * El principio de todos estos mensajes.
     *
     * <p>Es público porque el test lo comprueba: sirve para distinguir «lo rechaza el laboratorio»
     * de «lo rechaza HAPI», que es justamente la diferencia que este tipo existe para asegurar.
     */
    public static final String CABECERA = "El laboratorio no admite";

    /**
     * @param verbo cómo lo escribiría un cliente ({@code PATCH}, {@code $expunge}…)
     * @param porque qué se rompería si se admitiera, en una frase
     * @param enSuLugar qué debe hacer quien lo intentaba
     */
    public PuertaHeredadaCerrada(String verbo, String porque, String enSuLugar) {
        super("%s %s sobre este recurso: %s. En su lugar, %s.".formatted(CABECERA, verbo, porque, enSuLugar));
    }
}
