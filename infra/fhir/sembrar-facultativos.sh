#!/usr/bin/env bash
#
# Da de alta en el laboratorio los facultativos peticionarios que el HIS referencia.
#
#   infra/fhir/sembrar-facultativos.sh
#
# Por qué hace falta, y por qué NO lo hace el motor de integración
# ----------------------------------------------------------------
# Un `OML^O21` trae en `ORC-12` quién pide las pruebas, y el motor lo traduce a
# `ServiceRequest.requester = Practitioner/<número de colegiado>`. Si ese facultativo no está en el
# directorio, la API rechaza la petición y el mensaje acaba en la bandeja de errores del motor —
# que es la conducta correcta, y reprocesar después del alta lo aplica entero.
#
# Lo que estaba sin decidir era **quién da el alta**. Se decidió que un dato maestro se siembra
# aparte y que **el motor no gana ningún permiso nuevo**: dejarle crear facultativos lo convertiría
# en autoridad sobre un directorio que solo conoce de oídas, y un número de colegiado mal tecleado
# en el HIS crearía un facultativo fantasma que ya no se deshace sin borrar.
#
# Se siembra con el testigo de un **profesional**, que es quien mantiene el directorio en un
# laboratorio de verdad — el mostrador, no el canal de mensajería.
#
# Por qué es un `PUT` con id elegido
# ----------------------------------
# El HIS nombra al peticionario por su número de colegiado, así que ese número **es** el id lógico
# del recurso. Darlo de alta es por tanto una actualización con id elegido y no una creación: `.c`
# es crear y ahí el id lo asigna el servidor. De ahí el scope `user/Practitioner.u`.
#
# La alternativa —que el motor busque `Practitioner?identifier=<colegio>|COL12345` y referencie el
# UUID que devuelva el servidor— es más correcta en FHIR y toca el transformador, sus tests y el
# ejemplo de la guía. Queda anotada aquí.
set -euo pipefail

raiz=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
entorno=$raiz/infra/compose/.env

# shellcheck source=../entorno.sh
. "$raiz/infra/entorno.sh"
cargar_entorno "$entorno"

BASE_FHIR=${BASE_FHIR:-http://localhost:8080/fhir}
USUARIO=${HISPALIS_FACULTATIVO:-dra.alvarez}
CLAVE=${HISPALIS_KEYCLOAK_CLAVE_DEMO:-}
CLIENTE=hispalis-web
REDIR=${HISPALIS_REDIRECCION_WEB:-http://localhost:4200/callback}
SCOPES="openid fhirUser user/*.rs user/Practitioner.c user/Practitioner.u"
SID_COLEGIADO=https://aojeda006.github.io/HispaLIS/sid/colegiado

if [[ -z $CLAVE ]]; then
  echo "Falta HISPALIS_KEYCLOAK_CLAVE_DEMO. Copia infra/compose/.env.example a .env." >&2
  exit 78
fi

# ─── El testigo, por el flujo SMART de verdad ────────────────────────────────
#
# No hay atajo: `hispalis-web` es un cliente público con PKCE obligatorio y sin concesión directa
# de contraseña, que es como debe ser. Lo único que se sustituye aquí es el navegador — la
# contraseña la teclearía una persona en la pantalla de Keycloak.

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
galletas=$tmp/galletas

descubrimiento=$(curl -sf "$BASE_FHIR/.well-known/smart-configuration") || {
  echo "El laboratorio no contesta en $BASE_FHIR. ¿Está levantado el compose?" >&2
  exit 69
}
autorizar=$(python3 -c 'import json,sys;print(json.load(sys.stdin)["authorization_endpoint"])' <<<"$descubrimiento")
canjear=$(python3 -c 'import json,sys;print(json.load(sys.stdin)["token_endpoint"])' <<<"$descubrimiento")

read -r verificador estado reto < <(python3 - <<'PY'
import base64, hashlib, secrets
sr = lambda b: base64.urlsafe_b64encode(b).decode().rstrip("=")
v = sr(secrets.token_bytes(32))
print(v, sr(secrets.token_bytes(32)), sr(hashlib.sha256(v.encode()).digest()))
PY
)

pagina=$(curl -s -c "$galletas" -b "$galletas" -G "$autorizar" \
  --data-urlencode "response_type=code" \
  --data-urlencode "client_id=$CLIENTE" \
  --data-urlencode "redirect_uri=$REDIR" \
  --data-urlencode "scope=$SCOPES" \
  --data-urlencode "state=$estado" \
  --data-urlencode "aud=$BASE_FHIR" \
  --data-urlencode "code_challenge=$reto" \
  --data-urlencode "code_challenge_method=S256")

accion=$(grep -o 'action="[^"]*login-actions[^"]*"' <<<"$pagina" | head -1 | sed 's/^action="//; s/"$//' \
  | python3 -c 'import html,sys;print(html.unescape(sys.stdin.read().strip()))')
[[ -n $accion ]] || { echo "No se encontró el formulario de identificación de Keycloak." >&2; exit 69; }

vuelta=$(curl -s -o /dev/null -D - -c "$galletas" -b "$galletas" \
  --data-urlencode "username=$USUARIO" --data-urlencode "password=$CLAVE" "$accion" \
  | grep -i '^location:' | tail -1 | sed 's/^[Ll]ocation:[[:space:]]*//' | tr -d '\r')
[[ -n $vuelta ]] || { echo "El servidor de identidad no redirigió: ¿usuario o contraseña mal?" >&2; exit 69; }

devuelto=$(python3 -c 'import sys,urllib.parse as u;print(u.parse_qs(u.urlparse(sys.argv[1]).query).get("state",[""])[0])' "$vuelta")
[[ $devuelto == "$estado" ]] || { echo "El `state` de vuelta no es el que se mandó." >&2; exit 69; }
codigo=$(python3 -c 'import sys,urllib.parse as u;print(u.parse_qs(u.urlparse(sys.argv[1]).query).get("code",[""])[0])' "$vuelta")

testigo=$(curl -sf -X POST "$canjear" \
  --data-urlencode "grant_type=authorization_code" \
  --data-urlencode "code=$codigo" \
  --data-urlencode "redirect_uri=$REDIR" \
  --data-urlencode "client_id=$CLIENTE" \
  --data-urlencode "code_verifier=$verificador" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("access_token",""))')
[[ -n $testigo ]] || { echo "El canje del código falló." >&2; exit 69; }

# ─── El alta ─────────────────────────────────────────────────────────────────

fallos=0
while IFS= read -r facultativo; do
  id=$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["id"])' "$facultativo")

  # El apellido va ENTERO en `family` y las extensiones lo descomponen. Nunca se parte por el
  # espacio: «Muñoz de la Torre» son cuatro palabras y un solo apellido.
  cuerpo=$(SID="$SID_COLEGIADO" python3 - "$facultativo" <<'PY'
import json, os, sys

f = json.loads(sys.argv[1])
extensiones = [
    {"url": "http://hl7.org/fhir/StructureDefinition/humanname-fathers-family",
     "valueString": f["apellidoPadre"]}
]
if f.get("apellidoMadre"):
    extensiones.append({
        "url": "http://hl7.org/fhir/StructureDefinition/humanname-mothers-family",
        "valueString": f["apellidoMadre"],
    })

print(json.dumps({
    "resourceType": "Practitioner",
    "id": f["id"],
    "meta": {"profile": ["https://aojeda006.github.io/HispaLIS/fhir/StructureDefinition/facultativo-lab"]},
    "active": True,
    "identifier": [{
        "system": f"{os.environ['SID']}/{f['colegio']}",
        "value": f["id"],
        "assigner": {"display": f["colegioNombre"]},
    }],
    "name": [{
        "use": "official",
        "family": f["apellidos"],
        "given": [f["nombre"]],
        "_family": {"extension": extensiones},
    }],
}, ensure_ascii=False))
PY
)

  codigo=$(curl -s -o "$tmp/salida.json" -w '%{http_code}' -X PUT \
    -H "Authorization: Bearer $testigo" -H 'Content-Type: application/fhir+json' \
    "$BASE_FHIR/Practitioner/$id" --data-binary "$cuerpo")

  if [[ $codigo == 200 || $codigo == 201 ]]; then
    echo "Practitioner/$id — $codigo"
  else
    echo "Practitioner/$id — $codigo: $(cat "$tmp/salida.json")" >&2
    fallos=$((fallos + 1))
  fi
done < <(python3 -c 'import json,sys
for f in json.load(open(sys.argv[1], encoding="utf-8")):
    print(json.dumps(f, ensure_ascii=False))' "$raiz/infra/fhir/facultativos.json")

[[ $fallos -eq 0 ]] || exit 1

echo
echo "Directorio sembrado. Comprobación, releyendo lo que quedó:"
python3 -c 'import json,sys
for f in json.load(open(sys.argv[1], encoding="utf-8")):
    print(" ", f["id"])' "$raiz/infra/fhir/facultativos.json" \
  | while read -r id; do
      curl -sf -H "Authorization: Bearer $testigo" "$BASE_FHIR/Practitioner/$id" \
        | python3 -c 'import json, sys
r = json.load(sys.stdin)
n = r["name"][0]
partes = [e["valueString"] for e in n.get("_family", {}).get("extension", [])]
print("  %-10s %s %s   [apellidos sueltos: %s]" % (r["id"], " ".join(n["given"]), n["family"], ", ".join(partes)))'
    done
