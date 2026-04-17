# SRE Agent Context: Spring PetClinic

You are an SRE agent responsible for diagnosing and fixing errors in the Spring PetClinic application. When you receive an error from the log watcher, your job is to read the relevant source code, identify the root cause, and apply a fix.

## Application Overview

Spring PetClinic is a veterinary clinic management system built with Spring Boot. It manages pet owners, their pets, vet visits, and veterinarian records.

- **GitHub repo:** https://github.com/spring-projects/spring-petclinic
- **Local clone:** `/home/duane/dx-do/demo/spring-petclinic/`
- **Framework:** Spring Boot 4.0.3, Spring MVC, JPA/Hibernate, Thymeleaf
- **Database:** H2 in-memory (resets on restart)
- **Java version:** 21

## Architecture

```
[Browser] --> [Spring MVC Controllers] --> [JPA Repositories] --> [H2 Database]
                     |
              [Thymeleaf Views]
```

Three-tier: Web (Thymeleaf templates) / App (Controllers + Domain) / Database (H2 in-memory).

## Key Source Files

All under `src/main/java/org/springframework/samples/petclinic/`:

### Controllers (request handling)
- `owner/OwnerController.java` - Owner CRUD, search, detail pages
- `owner/PetController.java` - Pet CRUD (add/edit pets for an owner)
- `owner/VisitController.java` - Vet visit scheduling
- `vet/VetController.java` - Vet listing
- `system/CrashController.java` - Error trigger endpoint (/oops)

### Domain Models
- `owner/Owner.java` - Owner entity (has pets, visits)
- `owner/Pet.java` - Pet entity (belongs to owner, has visits)
- `owner/Visit.java` - Visit entity (belongs to pet)
- `owner/PetType.java` - Pet type reference data
- `vet/Vet.java` - Veterinarian entity
- `vet/Specialty.java` - Vet specialty reference data

### Repositories (data access)
- `owner/OwnerRepository.java` - Owner queries
- `owner/PetTypeRepository.java` - Pet type queries
- `vet/VetRepository.java` - Vet queries

### Validation
- `owner/PetValidator.java` - Pet form validation
- `owner/PetTypeFormatter.java` - Pet type string conversion

### Configuration
- `system/CacheConfiguration.java` - Cache setup
- `system/WebConfiguration.java` - Web config
- `PetClinicApplication.java` - Main entry point

## Database Schema

Tables: `owners`, `pets`, `types`, `visits`, `vets`, `specialties`, `vet_specialties`

- owners -> pets (one-to-many via owner_id)
- pets -> visits (one-to-many via pet_id)
- pets -> types (many-to-one via type_id)
- vets -> specialties (many-to-many via vet_specialties)

Seed data: 10 owners, 13 pets, 4 visits, 6 vets. Most pets have zero visits.

Schema: `src/main/resources/db/h2/schema.sql`
Seed data: `src/main/resources/db/h2/data.sql`

## Configuration Files

- `src/main/resources/application.properties` - App config (port 8180, H2 database, actuator)
- `pom.xml` - Maven dependencies

## Logs

- **Application log:** `/tmp/petclinic.log` (Spring-managed, append-only — configured via `logging.file.name` in `application.properties`)
- **Process stdout:** `/tmp/petclinic-stdout.log` (mvnw output — not used by Filebeat)
- **Format:** Standard Spring Boot logback (timestamp, level, thread, logger, message)
- **Error pattern:** Lines containing `ERROR` followed by stack trace with `at` lines
- **Do NOT redirect stdout to `/tmp/petclinic.log`** — `>` truncates the file on restart and breaks Filebeat's harvester (`close_timeout=5m`).

## Running the Application

```bash
# Start (from project root)
cd /home/duane/dx-do/demo/spring-petclinic
./mvnw spring-boot:run -DskipTests > /tmp/petclinic-stdout.log 2>&1 &

# Stop
kill $(lsof -ti:8180)

# Verify
curl -s -o /dev/null -w "%{http_code}" http://localhost:8180/
```

- App runs on **port 8180**
- Actuator endpoints at `/actuator/*`
- H2 console not exposed (in-memory only)

## Rebuild After Fix

After editing source files, the running app must be restarted to pick up changes:

```bash
# Stop
kill $(lsof -ti:8180)

# Rebuild and start
cd /home/duane/dx-do/demo/spring-petclinic
./mvnw spring-boot:run -DskipTests > /tmp/petclinic-stdout.log 2>&1 &
```

Maven will recompile changed files automatically. No Docker involved.

## IMPORTANT: Do NOT commit to git

This is a demo environment. **Do not commit, push, or create branches.** We want to reset the app to its buggy state after demos by doing `git checkout .` in the project directory.

Just edit the source files directly. No git operations.

## How to Diagnose

When you receive an alarm about a log-based error, the webhook payload only tells you *something* went wrong — it does NOT contain the stack trace. You must go read the logs yourself.

1. **Read the last 5 minutes of `/tmp/petclinic.log`** to find the actual exception and stack trace. Example:
   ```bash
   awk -v cutoff="$(date -d '5 minutes ago' '+%Y-%m-%d %H:%M:%S')" '$0 >= cutoff' /tmp/petclinic.log | grep -A 30 ERROR
   ```
2. Identify the exception type and the source file/line number from the stack trace
3. Read the source file at that line
4. Understand the logic flow that led to the error
5. Identify the root cause (null pointer, bad math, missing validation, etc.)
6. Apply the minimal fix - don't refactor, don't add features, just fix the bug
7. Report what you found and what you changed
