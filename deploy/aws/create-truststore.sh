#!/usr/bin/env bash
set -euo pipefail
# Accept an AWS-provided PEM bundle; passwords never appear in command arguments.
if [[ $# != 2 ]]; then
  echo "Usage: DB_TRUSTSTORE_PASSWORD=... $0 bundle.pem output.p12" >&2
  exit 2
fi
: "${DB_TRUSTSTORE_PASSWORD:?Set DB_TRUSTSTORE_PASSWORD}"
if [[ -e "$2" ]]; then
  echo "Output already exists; choose a new path for certificate rotation." >&2
  exit 2
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
awk -v dir="$work" '/-----BEGIN CERTIFICATE-----/{n++; file=dir "/cert-" n ".pem"} file!=""{print > file} /-----END CERTIFICATE-----/{close(file); file=""}' "$1"
shopt -s nullglob
certs=("$work"/cert-*.pem)
(( ${#certs[@]} > 0 )) || { echo "No certificates found" >&2; exit 2; }
imported=0
for cert in "${certs[@]}"; do
  # AWS requires root CAs only, not intermediates (certificate rotation).
  subject=$(openssl x509 -in "$cert" -noout -subject -nameopt RFC2253 | sed 's/^subject=//')
  issuer=$(openssl x509 -in "$cert" -noout -issuer -nameopt RFC2253 | sed 's/^issuer=//')
  [[ "$subject" == "$issuer" ]] || continue
  openssl verify -CAfile "$cert" "$cert" >/dev/null
  imported=$((imported + 1))
  keytool -importcert -noprompt -alias "$(basename "$cert" .pem)" \
    -file "$cert" -keystore "$work/truststore.p12" -storetype PKCS12 \
    -storepass:env DB_TRUSTSTORE_PASSWORD >/dev/null
done
(( imported > 0 )) || { echo "No self-signed root CA found" >&2; exit 2; }
install -m 600 "$work/truststore.p12" "$2"
