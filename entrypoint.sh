#!/bin/sh
set -e

if [ -n "$DATABASE_URL" ] && [ -z "$SPRING_DATASOURCE_URL" ]; then
  case "$DATABASE_URL" in
    postgres://*|postgresql://*)
      no_proto="${DATABASE_URL#*://}"
      creds="${no_proto%%@*}"
      hostpath="${no_proto#*@}"
      export SPRING_DATASOURCE_USERNAME="${creds%%:*}"
      export SPRING_DATASOURCE_PASSWORD="${creds#*:}"
      export SPRING_DATASOURCE_URL="jdbc:postgresql://${hostpath}?sslmode=require&connectTimeout=30&socketTimeout=60&tcpKeepAlive=true"
      ;;
  esac
fi

exec java \
  -Xmx256m \
  -XX:MaxMetaspaceSize=120m \
  -XX:ReservedCodeCacheSize=48m \
  -XX:+UseSerialGC \
  -Xss512k \
  -jar /app/app.jar
