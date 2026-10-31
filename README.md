# Apiary Management System

This project is built with **Kotlin**, **Spring Boot**, and **Gradle**. 

## Prerequisites
To run this project locally, you will need:
* **Docker Desktop** (must be running to host the PostgreSQL database)
* **IntelliJ IDEA** (recommended IDE)
* **JDK 21**

## Database Setup
Before starting the application, ensure your database container is running. Open your terminal in the project root and execute:
```bash
docker compose up -d
```

## How to run (IntelliJ IDEA)
1. Start Docker Desktop and verify the database container is running.
2. Open the project directory in IntelliJ IDEA.
3. Verify that the Project SDK is set to Temurin 21 (`File` -> `Project Structure` -> `SDK`).
4. Wait for IntelliJ to sync and download Gradle dependencies.
5. Navigate to the main application file (`src/main/kotlin/.../ApiaryManagementSystemApplication.kt`) and click the green "Play" button.

## How to run (Command Line / Terminal)
Make sure Docker Desktop and the database are running, then execute in the project root:


**To run the test suite:**
```bash
./gradlew clean test --info
```
