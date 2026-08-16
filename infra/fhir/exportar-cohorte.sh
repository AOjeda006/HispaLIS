#!/usr/bin/env bash
#
# Exporta una cohorte de vigilancia por Bulk Data, de principio a fin y contra el `compose`.
#
#   infra/fhir/exportar-cohorte.sh [Group/xxxx]
#
# Sin argumento coge la primera cohorte que encuentre.
#
# Por qué este script existe, y por qué crea un cliente y luego lo borra
# ---------------------------------------------------------------------
# `$export` exige `system/Group.rs` **y** `system/*.rs`, las dos, desde un cliente de sistema (D23).
# El *realm* versionado **define** esos dos ámbitos y **no se los da a nadie**, igual que hace con
# `system/*.cruds`: exportar doscientas historias de una vez no es un acto asistencial, y tener un
# cliente permanentemente capaz de hacerlo es exactamente el riesgo que la decisión evita.
#
# De ahí la forma de este script: **el permiso se concede, se usa y se retira**. Lo que ejecuta es el
# acto administrativo completo, no un atajo para saltárselo —
#
#   1. crea el cliente `almacen-analitico` con los dos ámbitos,
#   2. le registra una clave pública recién generada,
#   3. exporta con ella,
#   4. y borra el cliente al terminar, pase lo que pase (`trap`).
#
# La clave privada vive en un directorio temporal que el `trap` también se lleva. **Nunca** hay un
# secreto compartido: es SMART Backend Services de verdad, con `private_key_jwt`, igual que el motor.
# La diferencia con el motor es dónde vive la clave pública —él la publica en un JWKS y aquí se pega,
# porque un cliente que dura noventa segundos no necesita poder rotarla—.
set -euo pipefail

raiz=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
entorno=$raiz/infra/compose/.env

# shellcheck source=../entorno.sh
. "$raiz/infra/entorno.sh"
cargar_entorno "$entorno"

BASE_FHIR=${BASE_FHIR:-http://localhost:8080/fhir}
KEYCLOAK=${KEYCLOAK:-http://localhost:8081}
REALM=hispalis
CLIENTE=almacen-analitico
ADMIN=${HISPALIS_KEYCLOAK_ADMIN:-admin}
ADMIN_CLAVE=${HISPALIS_KEYCLOAK_ADMIN_CLAVE:-}
AMBITOS=('system/Group.rs' 'system/*.rs')

if [[ -z $ADMIN_CLAVE ]]; then
  echo "Falta HISPALIS_KEYCLOAK_ADMIN_CLAVE. Copia infra/compose/.env.example a .env." >&2
  exit 78
fi

tmp=$(mktemp -d)
idCliente=""
limpiar() {
  if [[ -n $idCliente && -n ${admin:-} ]]; then
    curl -s -o /dev/null -X DELETE -H "Authorization: Bearer $admin" \
      "$KEYCLOAK/admin/realms/$REALM/clients/$idCliente" || true
    echo "· permiso retirado: cliente $CLIENTE borrado del realm"
  fi
  rm -rf "$tmp"
}
trap limpiar EXIT

json() { python3 -c 'import json,sys;d=json.load(sys.stdin)
for c in sys.argv[1:]:
    d = d[int(c)] if isinstance(d, list) else d.get(c, "")
print(d if isinstance(d, str) else json.dumps(d))' "$@"; }

# ─── 1. El acto administrativo ───────────────────────────────────────────────

admin=$(curl -sf -X POST "$KEYCLOAK/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli \
  --data-urlencode "username=$ADMIN" --data-urlencode "password=$ADMIN_CLAVE" | json access_token)
[[ -n $admin ]] || { echo "No se pudo entrar como administrador del realm." >&2; exit 69; }

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$tmp/clave.pem" 2>/dev/null
publica=$(openssl pkey -in "$tmp/clave.pem" -pubout -outform DER | openssl base64 -A)

# `defaultClientScopes` no se puede fijar al crear: Keycloak asigna los suyos y luego se añaden.
curl -sf -o /dev/null -X POST "$KEYCLOAK/admin/realms/$REALM/clients" \
  -H "Authorization: Bearer $admin" -H 'Content-Type: application/json' \
  --data-binary @- <<JSON
{
  "clientId": "$CLIENTE",
  "name": "Almacén analítico — exportación de cohortes",
  "description": "Creado por infra/fhir/exportar-cohorte.sh y borrado al terminar.",
  "enabled": true, "publicClient": false, "protocol": "openid-connect",
  "standardFlowEnabled": false, "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": false, "serviceAccountsEnabled": true,
  "clientAuthenticatorType": "client-jwt",
  "attributes": {
    "use.jwks.url": "false",
    "jwt.credential.public.key": "$publica",
    "token.endpoint.auth.signing.alg": "RS256",
    "use.refresh.tokens": "false",
    "client_credentials.use_refresh_token": "false",
    "access.token.lifespan": "300"
  },
  "protocolMappers": [{
    "name": "aud del laboratorio", "protocol": "openid-connect",
    "protocolMapper": "oidc-audience-mapper", "consentRequired": false,
    "config": { "included.custom.audience": "$BASE_FHIR",
                "access.token.claim": "true", "introspection.token.claim": "true" }
  }]
}
JSON

idCliente=$(curl -sf -H "Authorization: Bearer $admin" \
  -G "$KEYCLOAK/admin/realms/$REALM/clients" --data-urlencode "clientId=$CLIENTE" | json 0 id)
[[ -n $idCliente ]] || { echo "El cliente no se creó." >&2; exit 69; }

for ambito in "${AMBITOS[@]}"; do
  idAmbito=$(curl -sf -H "Authorization: Bearer $admin" "$KEYCLOAK/admin/realms/$REALM/client-scopes" \
    | python3 -c 'import json,sys;print(next((s["id"] for s in json.load(sys.stdin) if s["name"]==sys.argv[1]), ""))' "$ambito")
  [[ -n $idAmbito ]] || { echo "El realm no define el ámbito «$ambito». ¿Volumen viejo sin reimportar?" >&2; exit 69; }
  curl -sf -o /dev/null -X PUT -H "Authorization: Bearer $admin" \
    "$KEYCLOAK/admin/realms/$REALM/clients/$idCliente/default-client-scopes/$idAmbito"
  echo "· concedido $ambito"
done

# ─── 2. El testigo, firmado con la clave privada ─────────────────────────────

canjear=$(curl -sf "$KEYCLOAK/realms/$REALM/.well-known/openid-configuration" | json token_endpoint)
b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
cabecera=$(printf '{"alg":"RS256","typ":"JWT"}' | b64)
# `aud` es el token_endpoint, no el laboratorio; `jti` nuevo; y cinco minutos como mucho.
#
# ⚠️ Y `iat`, que no lo pide la norma pero sí Keycloak: sin él contesta «Token expiration is too far
# in the future and iat claim not present in token» — es decir, mide la vida de la aserción desde
# `iat` y, a falta de `iat`, desde un margen suyo mucho más corto que cinco minutos.
cuerpo=$(python3 -c 'import json,sys,time,uuid
ahora=int(time.time())
print(json.dumps({"iss":sys.argv[1],"sub":sys.argv[1],"aud":sys.argv[2],
                  "jti":str(uuid.uuid4()),"iat":ahora,"exp":ahora+300}))' "$CLIENTE" "$canjear" | b64)
firma=$(printf '%s.%s' "$cabecera" "$cuerpo" | openssl dgst -sha256 -sign "$tmp/clave.pem" | b64)

testigo=$(curl -sf -X POST "$canjear" \
  -d grant_type=client_credentials \
  --data-urlencode "client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer" \
  --data-urlencode "client_assertion=$cabecera.$cuerpo.$firma" | json access_token)
[[ -n $testigo ]] || { echo "El canje de la aserción falló." >&2; exit 69; }
echo "· testigo obtenido, ámbitos: $(python3 -c 'import base64,json,sys
p=sys.argv[1].split(".")[1]; p+="="*(-len(p)%4)
print(json.loads(base64.urlsafe_b64decode(p)).get("scope",""))' "$testigo")"

api() { curl -s -H "Authorization: Bearer $testigo" "$@"; }

# ─── 3. La exportación ───────────────────────────────────────────────────────

cohorte=${1:-}
if [[ -z $cohorte ]]; then
  cohorte=$(api "$BASE_FHIR/Group?_count=1" | python3 -c 'import json,sys
d=json.load(sys.stdin)
e=d.get("entry") or []
print("Group/"+e[0]["resource"]["id"] if e else "")')
fi
[[ -n $cohorte ]] || { echo "No hay ninguna cohorte que exportar todavía." >&2; exit 69; }
echo "· cohorte: $cohorte"

cabeceras=$(api -D - -o /dev/null -X POST -H 'Prefer: respond-async' \
  -H 'Accept: application/fhir+json' "$BASE_FHIR/$cohorte/\$export")
estado=$(grep -i '^content-location:' <<<"$cabeceras" | sed 's/^[^:]*:[[:space:]]*//' | tr -d '\r')
echo "· \$export → $(grep -i '^HTTP/' <<<"$cabeceras" | tail -1 | tr -d '\r')"
echo "  Content-Location: $estado"
[[ -n $estado ]] || { echo "Sin Content-Location: la exportación no arrancó." >&2; exit 69; }

for _ in $(seq 1 60); do
  respuesta=$(api -D "$tmp/sondeo" -o "$tmp/manifiesto.json" "$estado")
  codigo=$(grep -i '^HTTP/' "$tmp/sondeo" | tail -1 | awk '{print $2}')
  [[ $codigo == 202 ]] || break
  sleep 1
done
echo "· sondeo → $codigo · $(grep -i '^expires:' "$tmp/sondeo" | tr -d '\r')"
[[ $codigo == 200 ]] || { echo "El sondeo no terminó bien: $(cat "$tmp/manifiesto.json")" >&2; exit 1; }

# Sin `f-string` a propósito: el programa entero viaja entre comillas simples de shell, así que las
# dobles de dentro no se pueden escapar y una interpolación con `s["type"]` dentro es un error de
# sintaxis de Python. Concatenar es más feo y funciona.
python3 -c 'import json,sys
m=json.load(open(sys.argv[1],encoding="utf-8"))
print("  transactionTime:", m["transactionTime"])
print("  requiresAccessToken:", m["requiresAccessToken"])
print("  output:", ", ".join(s["type"] + " → " + s["url"].rsplit("/", 1)[-1] for s in m["output"]))
print("  deleted:", m.get("deleted", []), "· error:", m.get("error", []))' "$tmp/manifiesto.json"

while read -r tipo url; do
  api "$url" -o "$tmp/$tipo.ndjson"
  echo "  $tipo.ndjson — $(wc -l <"$tmp/$tipo.ndjson") línea(s)"
done < <(python3 -c 'import json,sys
for s in json.load(open(sys.argv[1],encoding="utf-8"))["output"]: print(s["type"], s["url"])' "$tmp/manifiesto.json")

echo "· el Patient que sale:"
head -1 "$tmp/Patient.ndjson" 2>/dev/null | sed 's/^/    /'

# ─── 4. Que no se lleva filiación, comprobado sobre lo descargado ────────────

fugas=0
for aguja in name family given identifier telecom address birthDate\" ; do
  if grep -qi "\"$aguja\"" "$tmp"/*.ndjson 2>/dev/null; then
    echo "  ⚠ FUGA: «$aguja» aparece en el NDJSON" >&2
    fugas=$((fugas + 1))
  fi
done
[[ $fugas -eq 0 ]] && echo "· sin filiación en el NDJSON: ni nombre, ni identificador, ni dirección"

# ─── 5. Y el fichero se borra ────────────────────────────────────────────────

borrado=$(api -o /dev/null -w '%{http_code}' -X DELETE "$estado")
despues=$(api -o "$tmp/despues.json" -w '%{http_code}' "$estado")
echo "· DELETE → $borrado · y el sondeo pasa a $despues"
python3 -c 'import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
print("   ", d["issue"][0]["diagnostics"])' "$tmp/despues.json" 2>/dev/null || true
