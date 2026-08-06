package es.hispalis.backend.dominio.hecho;

/**
 * Lo que el laboratorio cuenta al exterior. Un tipo por cada escritura que alguien de fuera necesita
 * conocer.
 *
 * <p>Son <strong>hechos ocurridos</strong>, no órdenes: se nombran en pasado porque describen algo
 * que ya pasó y que no se puede deshacer negándose a consumirlo. Quien los reciba decide qué hacer.
 *
 * <p>El nombre se guarda tal cual en el {@code outbox} y será el que discrimine el tópico cuando
 * llegue el relay a Kafka, así que <strong>renombrar uno rompe a los consumidores</strong>.
 */
public enum TipoDeHecho {

    /** Un paciente nuevo en el laboratorio. */
    PACIENTE_REGISTRADO,

    /** Se corrigió su filiación. Sin decir qué: el suscriptor que lo necesite lee el recurso. */
    PACIENTE_ACTUALIZADO,

    /** Entró una línea de petición: hay trabajo pedido. */
    PETICION_REGISTRADA,

    /** Una línea que el laboratorio no va a hacer. Quien esperaba ese resultado deja de esperarlo. */
    LINEA_ANULADA,

    /** Llegó la muestra. */
    ESPECIMEN_REGISTRADO,

    /** Hay cifra. Todavía no es publicable: falta que alguien responda de ella. */
    RESULTADO_INFORMADO,

    /**
     * Un facultativo firmó el resultado.
     *
     * <p>Es el hecho del que cuelgan el {@code ORU^R01} saliente hacia el HIS y el notificador EDO
     * del hito 3. Los dos se disparan desde aquí, no desde un {@code if} escondido en un caso de uso.
     */
    RESULTADO_VALIDADO,

    /** El informe salió. */
    INFORME_EMITIDO
}
