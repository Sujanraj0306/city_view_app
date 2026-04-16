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
   # open in Android Studio, or:
   ./gradlew installDebug
   ```
   Point the app's API base URL at `http://10.0.2.2:8000` for the emulator, or your host's LAN IP for a physical device.

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
