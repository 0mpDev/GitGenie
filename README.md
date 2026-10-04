# GitGenie

**Chat with your GitHub repositories.** GitGenie signs you in with GitHub, indexes the code of any repo you choose, and lets you ask questions about it in plain English. Answers are grounded in the actual code and come with file citations that link back to GitHub.

It is a Retrieval-Augmented Generation (RAG) app: your code is split into chunks, embedded locally, stored in PostgreSQL with pgvector, and the most relevant chunks are sent to an LLM (served by Groq) with every question.

---

## Features

- **GitHub sign-in** with OAuth. Access tokens are stored encrypted.
- **Repository dashboard** that syncs the repos you own or collaborate on, with live indexing progress.
- **One-click indexing** that fetches files through the GitHub API, filters out noise (lock files, `node_modules`, build output, large files), chunks them and stores embeddings.
- **RAG chat** with streaming answers (Server-Sent Events) rendered as Markdown.
- **Citations** on every answer that link to the exact file on GitHub.
- **Chat sessions** per repository, saved in the database.
- **Light and dark theme.**
- **Free to run locally.** Embeddings run on your machine (ONNX MiniLM) and chat uses Groq's API.

## How it works

```
GitHub repo ──► file filter ──► chunker ──► local embeddings ──► pgvector
                                                                     │
Question ──► embed ──► similarity search (top 8 chunks) ◄────────────┘
                              │
                              ▼
                  prompt + code context ──► Groq LLM ──► streamed answer + citations
```

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 17+, Spring Boot 3.4.3, Spring Security (OAuth2 login), Spring Data JPA |
| AI | Spring AI 1.0.0, Groq (OpenAI-compatible API) for chat, ONNX `all-MiniLM-L6-v2` for embeddings |
| Database | PostgreSQL 16 with the pgvector extension |
| Frontend | Next.js 16, React 19, TypeScript, Tailwind CSS 4, shadcn/ui on Base UI, TanStack Query, Streamdown |

## Project structure

```
GitGenie/
├── backend/                 Spring Boot API
│   └── src/main/java/gitGenie/backend/
│       ├── config/          Security, CORS, async executor, token encryption
│       ├── controllers/     Auth, repos, chat endpoints
│       ├── entity/          JPA entities (User, Repository, ChatSession, ChatMessage)
│       ├── services/
│       │   ├── ai/          Retrieval, prompt building, SSE streaming, citations
│       │   ├── github/      GitHub API client and rate limiter
│       │   └── indexing/    File filter, chunker, indexing pipeline
│       └── security/        GitHub OAuth user service, current-user helper
└── client/                  Next.js frontend
    ├── app/                 Routes: /, /login, /dashboard, /chat/[repoId]
    ├── components/          Chat, dashboard, layout and UI components
    ├── hooks/               React Query hooks (auth, repos, chat)
    └── lib/                 API client and SSE stream parser
```

## Prerequisites

- **Java 17 or newer** and Maven (the wrapper `./mvnw` is included)
- **Node.js 20 or newer** and npm
- **PostgreSQL 16 with pgvector.** Docker is the easiest way to get this.
- A **Groq API key**: <https://console.groq.com/keys>
- A **GitHub OAuth App**: <https://github.com/settings/developers>

## Setup

### 1. Start PostgreSQL with pgvector

```bash
docker run -d --name gitgenie-db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=gitgenie \
  -p 5433:5432 \
  pgvector/pgvector:pg16
```

The app creates its tables and the vector table on first start.

### 2. Create a GitHub OAuth App

Go to **GitHub → Settings → Developer settings → OAuth Apps → New OAuth App**:

| Field | Value |
|---|---|
| Homepage URL | `http://localhost:3000` |
| Authorization callback URL | `http://localhost:8080/login/oauth2/code/github` |

Copy the **Client ID** and generate a **Client Secret**.

### 3. Set environment variables for the backend

| Variable | Required | Description |
|---|---|---|
| `GROQ_API_KEY` | yes | Your Groq key (starts with `gsk_`) |
| `GITHUB_CLIENT_ID` | yes | OAuth App client ID |
| `GITHUB_CLIENT_SECRET` | yes | OAuth App client secret |
| `GROQ_MODEL` | no | Chat model, default `openai/gpt-oss-120b` |
| `FRONTEND_URL` | no | Default `http://localhost:3000` |
| `CORS_ALLOWED_ORIGINS` | no | Default `http://localhost:3000` |
| `TOKEN_ENCRYPTOR_PASSWORD` | recommended | Password used to encrypt stored GitHub tokens. Change the default. |
| `TOKEN_ENCRYPTOR_SALT` | recommended | Hex salt for token encryption. Change the default. |

PowerShell:

```powershell
$env:GROQ_API_KEY="gsk_..."
$env:GITHUB_CLIENT_ID="..."
$env:GITHUB_CLIENT_SECRET="..."
```

macOS / Linux:

```bash
export GROQ_API_KEY=gsk_...
export GITHUB_CLIENT_ID=...
export GITHUB_CLIENT_SECRET=...
```

If you run from an IDE, add them to the run configuration instead (for IntelliJ: *Run → Edit Configurations → Environment variables*).

> Never commit keys or secrets. Use environment variables only.

### 4. Run the backend

```bash
cd backend
./mvnw spring-boot:run
```

The API starts on <http://localhost:8080>. The **first start downloads the embedding model** (about 90 MB), so it takes a little longer.

### 5. Run the frontend

```bash
cd client
npm install
npm run dev
```

Open <http://localhost:3000>. If your backend is not on port 8080, set `NEXT_PUBLIC_API_BASE_URL` in `client/.env.local`.

## Using the app

1. Click **Sign in with GitHub**.
2. On the dashboard, pick a repository and click **Index**. Wait until the status shows **Ready**.
3. Open the chat and ask questions such as *"Where is authentication handled?"* or *"Explain how the indexing pipeline works."*
4. Click a citation chip to open the referenced file on GitHub.

## API overview

All `/api/**` routes require a logged-in session cookie.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/auth/login-url` | OAuth login URL |
| GET | `/api/auth/me` | Current user |
| GET | `/api/repos?refresh=true` | Sync and list repositories |
| GET | `/api/repos/{id}` | Repository details |
| POST | `/api/repos/{id}/index` | Start indexing (async) |
| GET | `/api/repos/{id}/status` | Indexing progress |
| POST | `/api/chat/sessions` | Create a chat session |
| GET | `/api/chat/sessions?repositoryId=` | List sessions for a repository |
| GET | `/api/chat/sessions/{id}` | Messages in a session |
| POST | `/api/chat/sessions/{id}/messages` | Send a message (SSE stream) |

The chat stream emits these events: `user_message`, `token`, `assistant_message`, `done`, and `error`.

## Configuration

Main settings live in `backend/src/main/resources/application.properties`.

| Property | Default | Meaning |
|---|---|---|
| `app.indexing.max-file-bytes` | `102400` | Skip files larger than this |
| `app.indexing.chunk-size` | `800` | Approximate chunk size in characters |
| `app.github.api-delay-ms` | `50` | Pause between GitHub API calls while indexing |
| `spring.ai.vectorstore.pgvector.dimensions` | `384` | Must match the embedding model |

Supported file types include common source languages (Java, TypeScript, Python, Go, Rust, and others), Markdown, config files (YAML, JSON, TOML, XML), SQL and shell scripts. See `CodeFileFilter.java` for the full list.

## Troubleshooting

| Problem | Fix |
|---|---|
| `Could not resolve placeholder 'GROQ_API_KEY'` | The variable is not set in the environment that starts the backend |
| `UnknownHostException: raw.githubusercontent.com` on first start | Your network blocks the host. Change DNS (for example 8.8.8.8) or download the model files and set `spring.ai.embedding.transformer.onnx.modelUri` and `tokenizer.uri` to local `file:` paths |
| Chat returns `404` from `api.groq.com` | The configured model has been retired. Set `GROQ_MODEL` to a current one, listed at `https://api.groq.com/openai/v1/models` |
| `extension "vector" is not available` | Use a Postgres image or install that includes pgvector, such as `pgvector/pgvector:pg16` |
| Vector dimension mismatch after changing embedding models | Drop the table with `DROP TABLE vector_store;`, restart, and re-index your repos |
| Next.js `ChunkLoadError` | Delete the `client/.next` folder and restart `npm run dev`. Keeping the project outside OneDrive also helps. |
| GitHub login fails with `redirect_uri` mismatch | The callback URL in your OAuth App must be exactly `http://localhost:8080/login/oauth2/code/github` |

## Roadmap ideas

- Re-index on push with GitHub webhooks
- Smarter chunking by function or class
- Line-accurate citations
- Per-file filters and `.gitignore`-style include rules
- Docker Compose for one-command startup

