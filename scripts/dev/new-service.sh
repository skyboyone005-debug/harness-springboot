#!/usr/bin/env bash
# make new-service NAME=payment —— 从 templates/service 生成可运行、已通过契约测试的 Spring Boot 服务，并注册到根 pom
set -euo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
NAME="${1:?usage: new-service.sh <name>}"
[[ "$NAME" =~ ^[a-z][a-z0-9]*$ ]] || { echo "name must be lowercase alnum (it is also the Java package)" >&2; exit 1; }
DST="services/$NAME"; [ -e "$DST" ] && { echo "$DST exists" >&2; exit 1; }
CLASS="$(echo "${NAME:0:1}" | tr a-z A-Z)${NAME:1}"
T=templates/service
render() { sed "s/__NAME__/$NAME/g; s/__CLASS__/$CLASS/g" "$T/$1" > "$2"; }

mkdir -p "$DST/src/main/java/com/harness/$NAME" "$DST/src/main/resources" "$DST/src/test/java/com/harness/$NAME"
render pom.xml.tmpl               "$DST/pom.xml"
render openapi.yaml.tmpl          "$DST/src/main/resources/openapi.yaml"
render application.yml.tmpl      "$DST/src/main/resources/application.yml"
render Application.java.tmpl      "$DST/src/main/java/com/harness/$NAME/${CLASS}Application.java"
render ItemController.java.tmpl   "$DST/src/main/java/com/harness/$NAME/ItemController.java"
render ContractTest.java.tmpl     "$DST/src/test/java/com/harness/$NAME/${CLASS}ContractTest.java"

# 注册模块（插在 gateway 之前）
python3 - "$NAME" <<'PY'
import sys,re
name=sys.argv[1]; p='pom.xml'; s=open(p).read()
line=f"    <module>services/{name}</module>\n"
if line not in s:
    s=s.replace("    <module>services/gateway</module>\n", line+"    <module>services/gateway</module>\n")
    open(p,'w').write(s)
PY
echo "created $DST (module registered in pom.xml) — next: edit openapi.yaml, then: mvn -q -pl $DST -am test"
