from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    DATABASE_URL: str = "postgresql://civicshield:civicshield@postgres:5432/civicshield"
    KAFKA_BOOTSTRAP_SERVERS: str = "kafka:9092"
    KAFKA_CIVIC_REPORTS_TOPIC: str = "civic-reports"
    HDFS_NAMENODE: str = "hdfs://namenode:9000"
    HDFS_WEBHDFS_URL: str = "http://namenode:9870"
    HDFS_USER: str = "root"
    AI_SERVICE_URL: str = "http://ai-service:8001"

    JWT_SECRET: str = "change-me"
    JWT_ALGORITHM: str = "HS256"
    JWT_EXPIRE_MINUTES: int = 60 * 24

    GMAIL_USER: str = ""
    GMAIL_PASSWORD: str = ""
    POLICE_EMAIL: str = ""
    CORPORATION_EMAIL: str = ""


settings = Settings()
