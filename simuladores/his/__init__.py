"""Simulador del HIS de la clínica: emite `ADT^A01` y `ADT^A08` por MLLP.

Es el arnés con el que se prueba el canal de demografía del motor de integración de extremo a
extremo, y también la única forma de ejercitarlo a mano contra la pila del `compose`.

Habla **HL7 V2.5.1** (D12), declara siempre su juego de caracteres en `MSH-18` y sabe repetir un
`MSH-10` a propósito: la deduplicación del motor solo se puede probar con un emisor que insista.
"""
