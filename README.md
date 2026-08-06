# TimbreApi

API standalone en Spring Boot para emitir Documentos Tributarios Electronicos (DTE) chilenos, hablando directamente con el SII.

Estado: scaffold inicial. Documentacion completa pendiente (ver plan de implementacion).

## Desarrollo local

```bash
./mvnw spring-boot:run
```

La aplicacion arranca en el puerto `8081` por defecto (configurable via `PORT`).

## Health check

```
GET /api/v1/health
```

```json
{"status": "ok", "ambiente": "CERTIFICACION"}
```
