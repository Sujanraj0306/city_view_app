# CivicShield

A monorepo for the CivicShield platform: an Android client, a backend API, and an AI service, backed by Postgres, Kafka (+ Zookeeper), and an HDFS cluster.

## Repository layout

```
.
├── android-app/        # Android client application
├── backend/            # Backend API service (exposes port 8000)
├── ai-service/         # AI/ML inference service (exposes port 8001)
├── docker-compose.yml  # Orchestrates all backend infrastructure + services
├── .env.example        # Template for required environment variables
└── README.md
```

## Services

| Service     | Image / Source                                     | Host port → container   |
| ----------- | -------------------------------------------------- | ----------------------- |
| postgres    | `postgres:15-alpine` (db: `civicshield`)           | 5432 → 5432             |
| zookeeper   | `confluentinc/cp-zookeeper:7.4.0`                  | 2181 → 2181             |
| kafka       | `confluentinc/cp-kafka:7.4.0`                      | 29092 → 29092 (host access); internal `kafka:9092` |
| namenode    | `bde2020/hadoop-namenode:2.0.0-hadoop3.2.1-java8`  | 19870 → 9870 (UI), 19000 → 9000 (RPC) |
| datanode    | `bde2020/hadoop-datanode:2.0.0-hadoop3.2.1-java8`  | 19864 → 9864            |
| backend     | `./backend/Dockerfile`                             | 8000 → 8000             |
| ai-service  | `./ai-service/Dockerfile`                          | 8001 → 8001             |

> Hadoop/Kafka host-side ports are remapped to 19xxx to avoid collisions with other local stacks. In-cluster services always use the standard ports (e.g. `kafka:9092`, `hdfs://namenode:9000`).

## Prerequisites

- Docker 24+ and Docker Compose v2
- (For Android work) Android Studio Hedgehog or newer and a JDK 17
- ~8 GB free RAM (Hadoop + Kafka are memory-hungry)

## Setup before first run

All of the following live in a single `.env` file at the repo root. **Never commit this file** — it is already listed in [.gitignore](.gitignore).

1. **Copy the template**

   ```bash
   cp .env.example .env
   ```

2. **Fill in your Gemini API key** (`GEMINI_API_KEY`)
   - Get one free at [Google AI Studio](https://aistudio.google.com/app/apikey) → **Create API key**.
   - Paste it into `.env` on the `GEMINI_API_KEY=` line.
   - This key powers the image-evidence gate. Reports submitted without a valid key are rejected with HTTP 503.

3. **Fill in your Gmail sender address and App Password** (`GMAIL_USER`, `GMAIL_PASSWORD`)
   - `GMAIL_PASSWORD` is **not** your regular Gmail login — it is a 16-character [Google App Password](https://support.google.com/accounts/answer/185833). 2-Step Verification must be enabled on the account before you can generate one.
   - Generate at [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords) — pick "Mail" / "Other (custom name)".
   - Paste the 16 characters into `.env` (spaces are allowed, the Gmail SMTP client strips them).

4. **Fill in alert recipient addresses** (`POLICE_EMAIL`, `CORPORATION_EMAIL`)
   - `POLICE_EMAIL` receives helmet-violation alerts; `CORPORATION_EMAIL` receives pothole alerts.
   - For local testing, point both at a personal inbox you can check.

5. **(Optional) Generate a long JWT secret** (`JWT_SECRET`)

   ```bash
   openssl rand -hex 32
   ```

   Paste the output into `.env`. Any sufficiently long random string works — just don't leave the placeholder.

6. **(Optional) Firebase service account** for push notifications
   - Firebase Console → Project Settings → Service Accounts → *Generate new private key*.
   - Save the JSON to `backend/firebase-key.json`. Leave `FCM_CREDENTIALS_PATH` as the default in `.env`.
   - If you skip this, push notifications are silently no-op'd — the rest of the app still works.

Once `.env` is filled in, continue with the run instructions below.

## Step-by-step: running the stack

1. **Clone and enter the repo**
   ```bash
   git clone <your-fork-url> civicshield
   cd civicshield
   ```

2. **Create your `.env` file** from the template and fill in real values:
   ```bash
   cp .env.example .env
   # then edit .env and set GMAIL_USER, GMAIL_PASSWORD,
   # POLICE_EMAIL, CORPORATION_EMAIL, JWT_SECRET
   ```
   > `GMAIL_PASSWORD` must be a [Google App Password](https://myaccount.google.com/apppasswords), not your Gmail login.

3. **Build the application images** (backend + ai-service):
   ```bash
   docker compose build
   ```

4. **Start the full stack** in the background:
   ```bash
   docker compose up -d
   ```
   The first boot pulls several GB of images; give it a few minutes.

5. **Verify everything is healthy**:
   ```bash
   docker compose ps
   ```
   All services should report `running` / `healthy`. Useful UIs:
   - Backend API:       http://localhost:8000
   - AI service:        http://localhost:8001
   - HDFS NameNode UI:  http://localhost:19870
   - HDFS RPC (host):   `hdfs://localhost:19000`
   - Postgres:          `localhost:5432` (db `civicshield`)
   - Kafka (host):      `localhost:29092`

6. **Tail logs** for a specific service when debugging:
   ```bash
   docker compose logs -f backend
   docker compose logs -f ai-service
   ```

7. **Build and run the Android app** (separate from Compose):
   ```bash
   cd android-app
   # open in Android Studio (recommended), or from CLI:
   ./gradlew installDebug
   ```
   - Requires Android Studio Hedgehog+ and JDK 17.
   - Emulator default: `http://10.0.2.2:8000` is the host's `localhost`. For a physical device, override via `./gradlew installDebug -PCIVIC_BASE_URL=http://<your-lan-ip>:8000` or edit `android-app/gradle.properties`.
   - Login with the seeded test users: `user1 / pass123`, `user2 / pass123`, `admin / admin123`.

8. **Stop the stack** when you're done:
   ```bash
   docker compose down             # keep volumes
   docker compose down -v          # also wipe Postgres + HDFS data
   ```

## Development tips

- Changes to `backend/` or `ai-service/` require a rebuild:
  ```bash
  docker compose up -d --build backend ai-service
  ```
- To reach Kafka from code running on your host, use `localhost:29092`. From inside Compose, use `kafka:9092`.
- To reach HDFS from inside Compose, use `hdfs://namenode:9000`.

## Troubleshooting

- **Ports already in use**: stop any local Postgres/Kafka/Hadoop instances, or change the host-side port mappings in `docker-compose.yml`.
- **Namenode fails to start**: remove the `hadoop_namenode` volume (`docker compose down -v`) to re-format on next boot.
- **Backend can't reach Postgres**: wait for the Postgres healthcheck to pass — the backend starts only after `service_healthy`.
