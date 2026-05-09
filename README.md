# Pago Service - Sistema de Reserva de Hoteles

Microservicio de **pagos**. Integra **MercadoPago Checkout Pro** (sandbox/testing). Crea preferences, recibe webhooks de MP (con verificación HMAC-SHA256), consulta el estado real del pago contra la API de MP y publica eventos a Kafka topic `pago.events` que **ms-reserva** consume para confirmar o liberar reservas (SAGA — Ronda 5.3).

## Información del Servicio

| Propiedad | Valor |
|-----------|-------|
| Puerto | 8085 |
| Java | 21 |
| Spring Boot | 3.4.0 |
| Spring Cloud | 2024.0.1 |
| Context Path | `/api/v1` |
| Base de Datos | MySQL |
| Mensajería | **Kafka producer** (topic `pago.events`) |
| Gateway de pago | **MercadoPago** (`com.mercadopago:sdk-java` 2.1.x) — abstracción `PaymentGateway` |
| Validación JWT | **RS256** con clave pública RSA compartida |

## Arquitectura: puerto + adaptador

El dominio NO conoce al proveedor de pago. La pieza clave es la abstracción **`PaymentGateway`** (puerto hexagonal) — el `PagoService` y el `WebhookController` solo dependen de eso. Mañana podés agregar Culqi/Niubiz/Stripe creando otra implementación, sin tocar el core.

```
core/pago/ports/                    ← contrato (puerto)
  ├── PaymentGateway.java           interface
  ├── CheckoutSessionRequest.java   record (input)
  ├── CheckoutSessionResult.java    record (output)
  ├── WebhookEventType.java         enum (APPROVED, REJECTED, IGNORED)
  └── WebhookEventResult.java       record

infrastructure/payment/mercadopago/ ← adaptador concreto
  ├── MercadoPagoGateway.java       implementa PaymentGateway
  └── MercadoPagoBeans.java         @Configuration: setea access token + bean
```

## Estructura del Proyecto

```
ms-pago/
├── pom.xml
├── env.example                     ← copialo a .env y completalo en DEV
├── contracts/
│   └── pago-service-api.yaml
└── src/main/
    ├── java/com/hotel/pago/
    │   ├── PagoServiceApplication.java
    │   ├── api/
    │   │   ├── PagosController.java     ← POST /pagos, GET /pagos/{id}
    │   │   ├── WebhookController.java   ← POST /pagos/webhook/mercadopago (publico + HMAC)
    │   │   └── dto/
    │   ├── core/pago/
    │   │   ├── model/ (Pago, EstadoPago)
    │   │   ├── ports/ (PaymentGateway + DTOs)        ← puerto hexagonal
    │   │   ├── repository/PagoRepository.java
    │   │   └── service/PagoService.java              ← agnostico del proveedor
    │   ├── core/outbox/                              ← Outbox Pattern (Ronda 6)
    │   ├── infrastructure/
    │   │   ├── config/ (SecurityConfig, JwtConfig, KafkaConfig)
    │   │   ├── payment/mercadopago/                  ← adaptador MP
    │   │   ├── events/ (PagoEvent, PagoEventPublisher)
    │   │   ├── jobs/ (OutboxRelayJob, OutboxCleanupJob)
    │   │   └── security/
    │   └── helpers/exceptions/...
    └── resources/
        ├── application.yml         ← bootstrap minimo (config-server lo hidrata)
        └── db/migration/
            ├── V1__init_schema.sql
            └── V2__outbox_event.sql
```

## Endpoints

| Método | Endpoint | Auth |
|--------|----------|------|
| POST | `/api/v1/pagos` | JWT (lo llama ms-reserva con token tecnico) |
| GET | `/api/v1/pagos/{id}` | JWT |
| POST | `/api/v1/pagos/webhook/mercadopago` | **PÚBLICO** + verificación HMAC-SHA256 (`x-signature`) |

## Variables de Entorno

| Variable | Obligatoria | Descripción | Ejemplo (DEV) |
|----------|-------------|-------------|---------------|
| `CONFIG_IMPORT` | No | Import de Spring Cloud Config | `optional:configserver:http://localhost:8888` |
| `CONFIG_FAIL_FAST` | No | Falla rápido si config-server no responde | `false` (DEV) / `true` (PROD) |
| `SERVER_PORT` | No | Puerto HTTP (default 8085) | `8085` |
| `EUREKA_URL` | No | URL de Eureka | `http://localhost:8761/eureka` |
| `SPRING_DATASOURCE_URL` | **Sí** | JDBC URL MySQL | `jdbc:mysql://localhost:3307/pago_db` |
| `SPRING_DATASOURCE_USERNAME` | **Sí** | Usuario MySQL | - |
| `SPRING_DATASOURCE_PASSWORD` | **Sí** | Contraseña MySQL | - |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | No | Default `validate` (PROD-safe) | `validate` |
| `SPRING_JPA_SHOW_SQL` | No | Default `false` | `true` (DEV) |
| `KAFKA_BOOTSTRAP_SERVERS` | **Sí** | Brokers Kafka | `localhost:9092` |
| `KAFKA_PAGO_EVENTS_TOPIC` | No | Default `pago.events` | `pago.events` |
| `JWT_PUBLIC_KEY` | **Sí** | Clave pública RSA del auth-service (PEM 1 línea con `\n`) | - |
| `MERCADOPAGO_ACCESS_TOKEN` | **Sí** | Access token de la app de pruebas MP | `APP_USR-...` |
| `MERCADOPAGO_WEBHOOK_SECRET` | **Sí** | Clave secreta del webhook (Dashboard → Webhooks) | - |
| `MERCADOPAGO_NOTIFICATION_URL` | **Sí** | URL pública (ngrok en DEV) que MP llama | `https://abcd.ngrok-free.app/api/v1/pagos/webhook/mercadopago` |
| `PAGO_DEFAULT_CURRENCY` | No | Default `PEN` | `PEN` |
| `PAGO_SUCCESS_URL` | **Sí** | URL de redirect post-pago exitoso | `http://localhost:4200/reservas/success` |
| `PAGO_CANCEL_URL` | **Sí** | URL de redirect post-cancelación | `http://localhost:4200/reservas/cancel` |
| `CORS_ALLOWED_ORIGINS` | **Sí** | Origen permitido para CORS | `http://localhost:4200` |

## Schema Migrations (Flyway)

- `V1__init_schema.sql` — tabla `pago` con FK lógica a `reserva`, `gateway_payment_id` (preferenceId de MP), `gateway` (string), `estado` (enum string), `version` (optimistic lock).
- `V2__outbox_event.sql` — tabla outbox para publicar eventos a Kafka de forma transaccional.

## Configuración de MercadoPago

> 📘 **La guía paso a paso completa está en [`../MERCADOPAGO_SETUP.md`](../MERCADOPAGO_SETUP.md)** (raíz del proyecto). Te lleva de cero — sin cuenta MP — hasta tener access token, webhook secret y URL ngrok listos para el `.env`.

### Resumen rápido (ya conocés MP)

1. Andá al dashboard de MP Developers → creá una app → copiá el **Access Token de pruebas** (`APP_USR-...`) → `MERCADOPAGO_ACCESS_TOKEN`.
2. Levantá `ngrok http 8080` (al **gateway**, no a este servicio directo).
3. En la app → Webhooks → pegá la URL ngrok + `/api/v1/pagos/webhook/mercadopago` → marcá evento `payment` → guardá → copiá la **Clave secreta de firma** → `MERCADOPAGO_WEBHOOK_SECRET`.
4. La URL ngrok completa va en `MERCADOPAGO_NOTIFICATION_URL`.

### Tarjetas de prueba (referencia rápida)

| Tarjeta | Tipo |
|---------|------|
| `4009 1753 3280 6176` | Visa |
| `5031 7557 3453 0604` | Mastercard |

El resultado del pago lo decide el **nombre del titular**, no el número:

| Nombre del titular | Resultado |
|--------------------|-----------|
| `APRO` | ✅ Aprobado |
| `OTHE` | ❌ Rechazado |
| `CONT` | ⏳ Pendiente |
| `FUND` | ❌ Sin fondos |

(Lista completa de cardholder_name + más detalles en [`../MERCADOPAGO_SETUP.md`](../MERCADOPAGO_SETUP.md#paso-8-tarjetas-de-prueba-peruanas--el-truco-del-nombre).)

CVC: cualquier 3 dígitos. Vencimiento: cualquier futuro. DNI: cualquier 8 dígitos.

## Webhook de MP — el truco

A diferencia de Stripe, **MP no incluye el estado del pago en el webhook**. Solo manda:

```json
{ "type": "payment", "action": "payment.updated", "data": { "id": "1234567890" } }
```

Para conocer el estado real, el `MercadoPagoGateway`:

1. Verifica `x-signature` (HMAC-SHA256) en tiempo constante.
2. Lee `data.id` del payload (es el **paymentId**, NO el preferenceId).
3. Hace `GET /v1/payments/{id}` contra la API de MP usando el access token.
4. Lee `payment.status` y `payment.externalReference`.
5. El `externalReference` lo seteamos al crear el preference como el `pagoId` interno → con eso correlacionamos webhook ↔ Pago en BD.
6. Mapea `status` → `WebhookEventType` (approved → APPROVED; rejected/cancelled/refunded/charged_back → REJECTED; pending/in_process → IGNORED).

Esa lógica está OCULTA en el adaptador. El `WebhookController` solo recibe un `WebhookEventResult` normalizado.

## Outbox Pattern (Ronda 6)

Para evitar el problema de **dual-write** (DB commit + Kafka publish son sistemas separados, no atómicos), todo evento de dominio se persiste primero en `outbox_event` dentro de la **misma transacción** del cambio de estado del pago. Un job asincrónico lo publica a Kafka.

```
PagoService.marcarAprobado (@Transactional, llamado desde webhook):
  1. UPDATE pago SET estado='APPROVED' ...              ┐
  2. INSERT INTO outbox_event (PagoAprobado payload)    │ una sola tx
  3. COMMIT                                             ┘

OutboxRelayJob (@Scheduled fixedDelay=2s):
  SELECT * FROM outbox_event WHERE sent=0 ORDER BY id
    LIMIT 100 FOR UPDATE SKIP LOCKED
  → publica a Kafka (síncrono, .get())
  → UPDATE outbox_event SET sent=1, sent_at=NOW()
```

### Garantías

| Caso | Resultado |
|------|-----------|
| Tx de pago hace rollback | NO queda evento (atomicidad) |
| Kafka caído al ejecutar webhook | Evento en outbox; se publica cuando vuelva |
| App crashea durante el webhook | Tx rollback automático |
| Webhook duplicado de MP | `marcarAprobado` es idempotente (chequea `esTerminal()`) |
| Múltiples instancias del relay | `FOR UPDATE SKIP LOCKED` evita duplicados |
| Reintentos del producer | `enable.idempotence=true` evita duplicados a nivel broker |

`OutboxCleanupJob` cron `0 0 3 * * *` borra `sent=true` con `sent_at < now-7d`.

## Eventos Publicados (Kafka)

Topic: **`pago.events`**. Key: `reservaId` (orden por reserva).

| `eventType` | Trigger | Quién consume |
|-------------|---------|---------------|
| `PagoCreado` | Pago insertado + Preference creado | logging |
| `PagoAprobado` | Webhook MP con `payment.status=approved` | ms-reserva (5.3) → confirma reserva |
| `PagoRechazado` | Webhook MP con `payment.status` ∈ {rejected, cancelled, refunded, charged_back} | ms-reserva (5.3) → libera slots, marca PAGO_FALLIDO |

## Flujo del pago (de punta a punta)

```
1. ms-reserva → POST /api/v1/pagos { reservaId, monto, moneda: PEN }
2. ms-pago → Pago(PENDING) en DB (Tx1)
3. ms-pago → MercadoPagoGateway.createCheckoutSession(...)
4. MP → { preferenceId, init_point }
5. ms-pago → UPDATE Pago SET gateway_payment_id=preferenceId, checkout_url (Tx2 + outbox PagoCreado)
6. ms-pago → 201 { pagoId, checkoutUrl }

7. Frontend redirige al init_point
8. Usuario paga en página de MP (datos NUNCA tocan tu backend)

9a. Pago APROBADO:
    MP → POST /pagos/webhook/mercadopago (+ x-signature HMAC)
    MercadoPagoGateway: verifica firma → GET /v1/payments/{id} → status='approved'
                      → externalReference=pagoId → resuelve a WebhookEventResult.approved(pagoId)
    PagoService.marcarAprobado(pagoId) → UPDATE Pago=APPROVED → outbox PagoAprobado
9b. Pago RECHAZADO:
    MP → webhook con payment.status ∈ {rejected, cancelled, ...}
    MercadoPagoGateway → WebhookEventResult.rejected(pagoId, "MP status=rejected detail=...")
    PagoService.marcarRechazado(pagoId, errorMsg) → UPDATE Pago=REJECTED → outbox PagoRechazado
```

## Seguridad

- **Validación JWT**: RS256 con `JWT_PUBLIC_KEY` para `/pagos` y `/pagos/{id}`.
- **Webhook PÚBLICO**: NO requiere JWT — la autenticidad se verifica con HMAC-SHA256 sobre el body crudo + headers `x-signature` y `x-request-id`. La verificación es **constant-time** (`MessageDigest.isEqual`) para evitar timing attacks.
- **Sesiones**: STATELESS.
- **CORS**: deshabilitado (lo maneja el `api-gateway`).
- **PCI compliance**: los datos de tarjeta NUNCA pasan por este servicio. MercadoPago Checkout aloja la página de pago.

## Modelo de Datos

```
┌────────────────────────────┐
│         Pago               │
├────────────────────────────┤
│ id                         │
│ reserva_id (logico)        │
│ monto, moneda (PEN)        │
│ gateway = 'MERCADOPAGO'    │
│ gateway_payment_id         │ ← preferenceId de MP
│ estado                     │ ← PENDING | APPROVED | REJECTED
│ checkout_url               │
│ error_message              │
│ created_at, updated_at     │
│ version (optimistic)       │
└────────────────────────────┘
```

### Estados de Pago

| Estado | Descripción | Terminal |
|--------|-------------|----------|
| `PENDING` | Pago creado, Preference activa, esperando webhook | No |
| `APPROVED` | Webhook MP con `payment.status=approved` | Sí |
| `REJECTED` | Webhook MP con `payment.status` rechazado/cancelado/reembolsado | Sí |

Idempotencia: si el webhook llega 2 veces para el mismo pago APPROVED, el segundo se ignora. Igual del lado del relay con `enable.idempotence=true`.

## Ejecución Local (DEV)

```bash
# 1. Infra (MySQL + Kafka)
docker compose -f docker-compose.infra.yml up -d

# 2. Variables
cp env.example .env
# editar .env (especialmente MERCADOPAGO_*, JWT_PUBLIC_KEY)

# 3. Túnel para webhook
ngrok http 8080
# Copiar la URL ngrok → MERCADOPAGO_NOTIFICATION_URL en .env
# Pegarla también en el dashboard de MP > Webhooks
# Copiar la Clave secreta del dashboard → MERCADOPAGO_WEBHOOK_SECRET

# 4. Levantar (auth-service y reserva-service deben estar arriba)
mvn spring-boot:run

# Swagger UI
open http://localhost:8085/api/v1/swagger-ui.html
```

## Troubleshooting

| Síntoma | Causa probable | Solución |
|---------|----------------|----------|
| `Firma HMAC invalida` | `MERCADOPAGO_WEBHOOK_SECRET` no coincide con el del dashboard | Volver a copiar la Clave secreta desde Dashboard → Webhooks |
| `metadata.pagoId es requerido` | Bug interno (PagoService dejó de pasar metadata) | Revisar `PagoService.crearPago` |
| `Error MP API creando preference` | `MERCADOPAGO_ACCESS_TOKEN` inválido o de live mode | Verificar que sea de la pestaña "Pruebas" (`APP_USR-...`) |
| 401 en `/pagos` | JWT inválido / no enviado | Token de auth-service o de ms-reserva (token técnico) |
| Webhook llega pero `Pago no encontrado` | `external_reference` no matchea pagoId | Verificar logs del gateway: `Payment X (pagoId=Y) status=Z` |
| Eventos Kafka no publicados | Kafka caído / `KAFKA_BOOTSTRAP_SERVERS` mal | `docker compose -f docker-compose.infra.yml ps`, ver outbox |
| Webhook nunca llega | URL de ngrok cambió o no la actualizaste en el dashboard | Inspeccionar `http://localhost:4040` (ngrok web UI) |
| `Could not resolve placeholder 'mercadopago.access-token'` | Falta env var | Revisar `.env` y EnvFile en IntelliJ Run Configuration |
