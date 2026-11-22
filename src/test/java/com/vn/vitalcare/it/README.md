# Integration tests (Report 5.2)

Each test runs the whole Spring Boot application against a real PostgreSQL database: security filter chain →
controller → Bean Validation → service → repository / SQL → database, and back. Nothing in the system is mocked;
the only stand-ins are for systems outside it:

- the SMS gateway (`TextedCodes` keeps the last code "texted" to a number, so a test types it like a person would);
- the payment gateways SePay and VNPay, whose calls `BillingIT` signs with test secrets the way they sign theirs;
- the MQTT broker, whose authentication hooks `SyncIT` calls with a test hook secret.

## Two kinds of suite

| Base class | Transaction | Used by |
|---|---|---|
| `IntegrationTestBase` | each test rolls back | the first suites (Account, Login, Logout, FE-01 account, Guest security, Public site, First aid, Guest chat, Appointment flow), which also change seed rows |
| `CommittedIntegrationTest` | every request commits, as in production | Device, VitalSign, Alert, Patient, Care, Schedule, Sharing, ClinicStaff, Encounter, Billing, Chat, Admin, License, Sync, VisitExtras |

The committed suites catch what a rollback transaction hides: with `spring.jpa.open-in-view=false`, a response built
after the service's transaction has ended cannot load a lazy association. They found DEF-ALR-01, DEF-MON-01,
DEF-SHR-01 and DEF-SYN-01 this way. They also let what runs after commit run (alerts raised by AlertEngine, notifications).
They only create data of their own (097 numbers, `IT-` device codes, rooms named "Phòng hô hấp thử") and never change
seed rows, so the database stays usable run after run.

## Running

1. PostgreSQL at `localhost:5432`, user `postgres` / `1234`, with a database of its own:
   `createdb -U postgres vitalcare_it` (Liquibase builds the schema and seed on the first run).
2. `./mvnw test -Dtest='*IT'`

Booking for today closes after the doctors' last session (23:55): do not run between 23:55 and midnight.
