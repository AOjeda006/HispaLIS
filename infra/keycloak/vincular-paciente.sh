#!/usr/bin/env bash
#
# Vincula una identidad del realm con una historia del laboratorio.
#
#   infra/keycloak/vincular-paciente.sh paciente.demo Patient/1a2b3c
#
# Por qué esto es un guion y no dos líneas más en `hispalis-realm.json`
# ---------------------------------------------------------------------
# El realm es CONFIGURACIÓN: clientes, scopes, roles, qué atributos existen. Todo eso se puede
# escribir antes de que la pila arranque porque no depende de nada.
#
# La vinculación de una persona con su historia clínica es DATO, y además dato que no existe hasta
# que el laboratorio ha creado el `Patient`: el id lógico lo asigna el servidor en tiempo de
# ejecución. Meterlo en el realm exigiría inventarse un id y hacer que el laboratorio lo respetara,
# que es exactamente al revés de como funciona FHIR.
#
# En un despliegue de verdad esto lo hace el **alta del paciente** —el mostrador, o el proceso que
# da de alta la identidad digital—, no un fichero de configuración. Aquí se hace con `kcadm` porque
# es la pila de desarrollo, pero el momento es el mismo: cuando la historia ya existe.
set -euo pipefail

usuario=${1:-}
referencia=${2:-}

if [[ -z $usuario || -z $referencia ]]; then
  cat >&2 <<'AYUDA'
Uso: infra/keycloak/vincular-paciente.sh <usuario> <Patient/id>

  usuario      El de Keycloak: paciente.demo, paciente.otro…
  Patient/id   La referencia FHIR de su historia en el laboratorio.

Para saber el id, pregúntaselo al laboratorio por un identificador que conozcas:

  curl -s -H "Authorization: Bearer $TESTIGO" \
    'http://localhost:8080/fhir/Patient?identifier=urn:oid:1.3.6.1.4.1.99999.1|NHC-000001' \
    | python -c 'import json,sys; print(json.load(sys.stdin)["entry"][0]["resource"]["id"])'
AYUDA
  exit 64
fi

if [[ ! $referencia =~ ^Patient/[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; then
  echo "«$referencia» no es una referencia FHIR a un paciente. Se espera Patient/<id>." >&2
  exit 64
fi

raiz=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
entorno=$raiz/infra/compose/.env

# Las credenciales de administración salen del mismo `.env` que usa el `compose`. No hay valor por
# defecto para la contraseña: es un secreto de verdad.
# shellcheck source=../entorno.sh
. "$raiz/infra/entorno.sh"
cargar_entorno "$entorno"

admin=${HISPALIS_KEYCLOAK_ADMIN:-admin}
clave=${HISPALIS_KEYCLOAK_ADMIN_CLAVE:-}
if [[ -z $clave ]]; then
  echo "Falta HISPALIS_KEYCLOAK_ADMIN_CLAVE. Copia infra/compose/.env.example a .env." >&2
  exit 78
fi

# Contra la API de administración por HTTP y no con `kcadm` dentro del contenedor. Se intentó con
# `kcadm` y se descartó: `docker compose exec -T` no le hace llegar la entrada estándar de forma
# fiable, y la forma con `-s attributes.x=[...]` **no falla** cuando no cuaja — contesta que todo ha
# ido bien y deja los atributos vacíos. Una orden que miente es peor que una que no existe.
KEYCLOAK=${HISPALIS_KEYCLOAK_URL:-http://localhost:8081}

testigo=$(curl -sf -X POST "$KEYCLOAK/realms/master/protocol/openid-connect/token"   -d grant_type=password -d client_id=admin-cli   --data-urlencode "username=$admin" --data-urlencode "password=$clave"   | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])') || {
  echo "No se pudo entrar en $KEYCLOAK como administrador. ¿Está levantado el compose?" >&2
  exit 69
}

id=$(curl -sf -H "Authorization: Bearer $testigo"   "$KEYCLOAK/admin/realms/hispalis/users?exact=true&username=$usuario"   | python3 -c 'import json,sys;u=json.load(sys.stdin);print(u[0]["id"] if u else "")')

if [[ -z $id ]]; then
  echo "En el realm hispalis no hay ningún usuario «$usuario»." >&2
  exit 1
fi

# El `fhirUser` lleva el tipo delante y el contexto de lanzamiento NO: son dos formas distintas de
# nombrar lo mismo y la norma SMART las define así. Confundirlas hace que el laboratorio conteste
# 403 sin más explicación. Y los dos son LISTAS: un atributo de usuario de Keycloak siempre lo es.
paciente=${referencia#Patient/}

codigo=$(curl -s -o /dev/null -w '%{http_code}' -X PUT   -H "Authorization: Bearer $testigo" -H 'Content-Type: application/json'   "$KEYCLOAK/admin/realms/hispalis/users/$id"   -d "{\"attributes\":{\"fhirUser\":[\"$referencia\"],\"patient\":[\"$paciente\"]}}")

if [[ $codigo != 204 ]]; then
  echo "Keycloak contestó $codigo al vincular. No se ha cambiado nada." >&2
  exit 1
fi

echo "«$usuario» queda vinculado a $referencia."
echo "Comprobación:"
curl -sf -H "Authorization: Bearer $testigo" "$KEYCLOAK/admin/realms/hispalis/users/$id"   | python3 -c 'import json,sys;u=json.load(sys.stdin);print(" ",u["username"],"→",u.get("attributes"))'
