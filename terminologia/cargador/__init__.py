"""Carga del servidor de terminología de HispaLIS (D14).

Extrae de las releases archivadas **fuera del repositorio** el subconjunto que la guía
referencia y lo publica en el servidor por la API estándar de FHIR. Ni la terminología
licenciada ni el subconjunto extraído se versionan nunca.
"""

__all__ = ["curado", "loinc", "publicacion", "snomed", "tho"]
