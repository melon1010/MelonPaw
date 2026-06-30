# QwenPaw Console for Java Backend

This directory is a copy of the QwenPaw frontend from:

`/Users/melon/IdeaWorkSpace/Github/QwenPaw/console`

It is kept in this Java project so the frontend can be started next to the Spring Boot backend and pointed at the Java-compatible APIs.

## Run

Start the Java backend first:

```bash
mvn -pl melon-app spring-boot:run
```

Then start the console:

```bash
cd console
npm install
npm run dev
```

By default, Vite runs on `http://localhost:5173` and proxies `/api` to `http://localhost:8088`.

If you want to bypass the Vite proxy, set:

```bash
VITE_API_BASE_URL=http://localhost:8088 npm run dev
```

## Scope

The frontend source should stay close to upstream QwenPaw. Prefer backend compatibility fixes in the Java controllers/services before changing frontend API calls.

Desktop/Tauri packaging and client code are intentionally not included in this Java project copy.
