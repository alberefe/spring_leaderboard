# Real-Time Leaderboard Backend with Spring Boot and Redis — Roadmap


Goal: Build a backend for a real-time leaderboard service supporting user authentication, score submission, live leaderboard updates, and score history/reporting, using Redis Sorted Sets for ranking.

This roadmap is designed to be practical and progressive. It includes:
- A step-by-step plan to build the project from scratch.
- At each step: key Spring concepts and general CS topics to read about.
- The main classes you will implement, with methods and purpose.
- Warnings, pitfalls, and tips for success.


## 0) High-level Architecture and Data Model

- API layer: REST endpoints for auth, scores, leaderboards, and reports.
- Real-time layer: WebSocket (STOMP) broadcasting of leaderboard updates.
- Storage:
  - PostgreSQL: durable storage for users, games, and score history (for reports).
  - Redis: ephemeral but extremely fast leaderboards using Sorted Sets (ZSET).
- Security: Spring Security with JWT (stateless) for API, optional CSRF disabled for APIs.
- Observability: Spring Boot Actuator for health/metrics.

Data concepts:
- User: id, username, password (hashed), roles.
- Game: id, code/key, name.
- Score submission: userId, gameId, score, submittedAt.
- Leaderboards:
  - Per-game: ZSET key like lb:game:{gameId} (member=userId, score=highestScore).
  - Global: ZSET key lb:global (member=userId, score=aggregate across games, e.g., sum or max between games depending on business rule).
  - Time-windowed sets for reports: lb:game:{gameId}:daily:{yyyyMMdd}, weekly, monthly.


## 1) Project Setup & Bootstrapping

Deliverables:
- Spring Boot project (already scaffolded in this repo).
- Base configuration for PostgreSQL, Redis, and profiles (dev/test/prod).

Spring topics to read:
- Spring Boot auto-configuration and configuration properties.
- Spring Data JPA basics.
- Spring Data Redis and RedisTemplate/StringRedisTemplate.
- Spring Profiles.

CS/Infra topics to read:
- Redis basics, persistence (RDB/AOF), and ZSET operations (ZADD, ZSCORE, ZRANGE, ZREVRANK, ZREVRANGE, ZUNION, ZINTER, ZREMRANGEBYRANK, ZRANGE BYSCORE).
- JDBC connection pools (HikariCP).
- Environment-based configuration and secrets handling.

Classes and files to add:
- RedisConfig
  - redisConnectionFactory(): LettuceConnectionFactory or reuse auto-config.
  - stringRedisTemplate(): StringRedisTemplate
  - redisTemplate(): RedisTemplate<String, Object> with Jackson serializers (optional).
  - Why: strongly-typed access to Redis and reliable JSON serialization for complex objects.
- DataSource/JPAAuto-config is largely auto-managed, but ensure application.properties includes DB and Redis settings.

Warnings/tips:
- Use Spring profiles. For dev, you can rely on Testcontainers or local Redis/Postgres.
- Ensure time zone consistency (UTC) in your app and DB. Use Instant in Java.


## 2) Domain Modeling (Users, Roles, Games, Scores)

Deliverables:
- JPA Entities and Repositories.

Spring topics to read:
- JPA annotations (@Entity, @Id, @GeneratedValue, @Column, @ManyToOne, @OneToMany, @Enumerated).
- Repository interfaces (CrudRepository/JpaRepository).

CS topics to read:
- Password hashing (BCrypt), normalization, uniqueness constraints.
- Database migrations (Flyway) for schema versioning.

Classes and methods:
- UserEntity
  - fields: id (UUID), username (unique), passwordHash, roles (Set<Role> or a simple enum), createdAt
  - Why: secure storage of user identity for authentication.
- Role (enum or Entity)
  - USER, ADMIN.
- GameEntity
  - fields: id (UUID), code (unique), name, createdAt
  - Why: catalog of games/activities.
- ScoreHistoryEntity
  - fields: id (UUID), userId, gameId, score (long/double), submittedAt (Instant)
  - Why: durable audit trail and basis for reports.
- Repositories
  - UserRepository: findByUsername(String)
  - GameRepository: findByCode(String)
  - ScoreHistoryRepository: queries by userId/gameId/time ranges

Warnings/tips:
- Use Flyway migrations to create tables with unique constraints for username and game code.
- Prefer UUID (binary) or ULID identifiers.
- Store timestamps as UTC (TIMESTAMP WITH TIME ZONE or Instant mapped correctly).


## 3) Authentication & Authorization (JWT)

Deliverables:
- Register and Login endpoints. Secure other endpoints with JWT.

Spring topics to read:
- Spring Security 6/Boot 3 configuration (SecurityFilterChain).
- PasswordEncoder (BCryptPasswordEncoder).
- Custom AuthenticationProvider or DaoAuthenticationProvider.
- OncePerRequestFilter for JWT.

CS topics to read:
- JWT structure, signing (HS256/RS256), token expiration/refresh.
- OWASP API Security Top 10.

Classes and methods:
- SecurityConfig
  - securityFilterChain(HttpSecurity): configure stateless sessions, permit /auth/**, secure others.
  - passwordEncoder(): BCryptPasswordEncoder
  - authenticationManager(AuthenticationConfiguration)
  - Why: define how requests are authenticated and which paths are open.
- JwtTokenService
  - generateToken(UserDetails user, Duration ttl)
  - parseAndValidate(String token): Optional<JwtClaims>
  - getUsername(String token)
  - Why: create and validate JWTs.
- JwtAuthFilter (extends OncePerRequestFilter)
  - doFilterInternal(...): extract Bearer token, validate, set SecurityContext.
  - Why: apply JWT auth to every request after login.
- AuthController
  - POST /auth/register: RegisterRequest -> AuthResponse
  - POST /auth/login: LoginRequest -> AuthResponse
- AuthService
  - register(username, rawPassword): creates user, hashes password.
  - authenticate(username, rawPassword): validates credentials.

Warnings/tips:
- Never store plain passwords. Always use BCrypt (strength 10–12 for dev).
- Set reasonable JWT expiration and consider refresh tokens later.
- Return minimal user info in responses.


## 4) Leaderboard Core (Redis Sorted Sets)

Deliverables:
- Service to manage per-game and global leaderboards.
- Core operations: submit score, get rank, get top N, get around user, remove/reset.

Spring topics to read:
- StringRedisTemplate vs RedisTemplate.
- Redis scripts with Spring (DefaultRedisScript) if you need atomic logic.

CS/Redis topics to read:
- ZADD options: NX (only add), XX (only update), CH (return changed), GT/LT (only if greater/less).
- ZSCORE, ZREVRANK, ZREVRANGE WITHSCORES, ZRANGE BYSCORE.
- ZUNION/ZINTER for aggregations.
- Key naming conventions and tags (use : as separator).

Key design:
- Per-game leaderboard: lb:game:{gameId}
- Global leaderboard: lb:global
- Time windows: lb:game:{gameId}:daily:{yyyyMMdd}, weekly, monthly
- Member convention: userId as string (UUID); Score as double (consider fixed-point if precision matters).

Classes and methods:
- LeaderboardKeyFactory
  - gameLeaderboardKey(UUID gameId)
  - globalLeaderboardKey()
  - dailyKey(UUID gameId, LocalDate date)
  - weeklyKey(UUID gameId, YearWeek week)
  - Why: centralizes key naming to avoid bugs.
- LeaderboardService
  - submitScore(UUID userId, UUID gameId, double score, Instant submittedAt):
    - Updates per-game ZSET with ZADD GT so only higher scores replace previous.
    - Updates global ZSET using aggregation rule (e.g., sum of user’s per-game top scores or max). If sum, recompute using ZSCORE from each per-game or maintain running sum.
    - Writes to time-windowed ZSETs for reports (e.g., daily).
  - getUserRankInGame(UUID userId, UUID gameId): Optional<RankEntry>
  - getTopNInGame(UUID gameId, int n): List<RankEntry>
  - getAroundUserInGame(UUID gameId, UUID userId, int before, int after): List<RankEntry>
  - getGlobalTopN(int n): List<RankEntry>
  - getUserGlobalRank(UUID userId): Optional<RankEntry>
  - removeUserFromGame(UUID userId, UUID gameId): for admin/testing
  - resetGame(UUID gameId): for admin/testing
  - Why: abstraction over Redis operations.
- RankEntry (DTO)
  - userId, rank (0-based or 1-based, be consistent), score

Warnings/tips:
- Use ZADD GT to enforce "highest score wins" per game. If your rule is "cumulative", use ZINCRBY instead.
- Aggregating global leaderboard: Start simple (sum of top per-game scores) and implement periodic recompute job to avoid heavy runtimes on every submit.
- Consider Lua scripts if you need cross-key atomicity (e.g., per-game update and global update together).


## 5) Score Submission & History

Deliverables:
- REST endpoint for score submission that updates Redis and logs history in Postgres.

Spring topics to read:
- @Transactional for DB writes; note that Redis operations are outside JPA transaction boundaries.
- ApplicationEventPublisher or transactional outbox pattern if you later need async processing.
- Bean Validation for request DTOs.

CS topics to read:
- Idempotency keys for repeated submissions.
- Time-series storage basics.

Classes and methods:
- ScoreController
  - POST /api/scores: SubmitScoreRequest -> SubmitScoreResponse
  - GET /api/scores/history?gameId=&from=&to= : Page<ScoreHistoryDto>
- ScoreService
  - submitScore(AuthenticatedUser, UUID gameId, double score, Instant submittedAt, Optional<String> idempotencyKey>)
    - Validates game exists, user authenticated, score bounds.
    - Writes ScoreHistoryEntity to DB.
    - Calls LeaderboardService.submitScore(...).
    - Optionally caches idempotencyKey in Redis with short TTL to avoid duplicates.
  - getUserHistory(UUID userId, UUID gameId, Instant from, Instant to, Pageable)

Warnings/tips:
- Decide if scores can go down. If "only best", keep DB history but use ZADD GT in Redis.
- Use validation annotations (@NotNull, @Positive, @PastOrPresent) in DTOs.


## 6) Real-time Updates (WebSocket/STOMP)

Deliverables:
- Clients can subscribe to topics to receive live leaderboard updates when new scores arrive.

Spring topics to read:
- spring-boot-starter-websocket, STOMP over WebSocket.
- SimpMessagingTemplate for sending messages.
- WebSocket security with Spring Security.

CS topics to read:
- Back-pressure and message rate limiting.
- Client subscription design.

Classes and methods:
- WebSocketConfig
  - registerStompEndpoints(…): /ws endpoint, with SockJS fallback if desired.
  - configureMessageBroker(…): enableSimpleBroker with destinations like /topic/leaderboard/{gameId}, /topic/leaderboard/global
- LeaderboardUpdatePublisher
  - publishGameUpdate(UUID gameId, RankEntry updatedEntry)
  - publishGlobalUpdate(RankEntry)
  - Why: decouple business logic from transport; call this after score submission.
- Message payloads: simple DTOs with userId, new score, rank snapshot.

Warnings/tips:
- Authenticate STOMP connects; propagate auth principal to restrict who can publish.
- Avoid flooding: batch updates or limit broadcast frequency.


## 7) Reports: Top Players over a Period

Deliverables:
- Endpoint to query top players for a time window (e.g., daily, weekly, monthly) per game and optionally global.

Spring topics to read:
- Scheduling (@EnableScheduling, @Scheduled) if you need periodic rebuilds.
- Spring Data JPA aggregate queries.

CS/Redis topics to read:
- Time-windowed leaderboards via separate ZSETs (daily/weekly) or rolling windows using ZREMRANGEBYSCORE and per-submission ZADD.
- ZUNIONSTORE to combine multiple days into a weekly or monthly leaderboard.

Classes and methods:
- ReportService
  - getTopPlayersForPeriod(UUID gameId, Instant from, Instant to, int n)
    - Approach A (Redis-first): If your data model keeps daily ZSETs, union the ZSETs with ZUNION and fetch top N.
    - Approach B (DB fallback): Query ScoreHistoryEntity aggregate SUM(max per user or sum per submission) for the window.
  - Why: supports both fast path and correctness fallback.
- ReportController
  - GET /api/reports/top?gameId=&from=&to=&limit=

Warnings/tips:
- Decide fairness: For per-period, should you aggregate best score per user in the period, or sum of all submissions? Keep consistent rules with the rest of the system.
- Keep per-period ZSETs with reasonable TTL (e.g., keep 90 days). Use Redis key TTLs or scheduled cleanup.


## 8) API Design (DTOs & Controllers)

Endpoints (proposed):
- Auth
  - POST /auth/register {username, password}
  - POST /auth/login {username, password}
- Games
  - POST /api/games {code, name} [ADMIN]
  - GET /api/games
- Scores
  - POST /api/scores {gameId, score, submittedAt?}
  - GET /api/scores/history?gameId=&from=&to=&page=&size=
- Leaderboards
  - GET /api/leaderboards/game/{gameId}/top?limit=50
  - GET /api/leaderboards/game/{gameId}/rank/{userId}
  - GET /api/leaderboards/game/{gameId}/around/{userId}?before=3&after=3
  - GET /api/leaderboards/global/top?limit=50
  - GET /api/leaderboards/global/rank/{userId}
- Reports
  - GET /api/reports/top?gameId=&from=&to=&limit=50

DTOs to define:
- RegisterRequest, AuthResponse (token, expiresAt)
- LoginRequest
- SubmitScoreRequest (gameId, score, submittedAt?, idempotencyKey?)
- SubmitScoreResponse (accepted, userRank, gameRank)
- RankEntryDto (userId, rank, score)
- ScoreHistoryDto
- ErrorResponse (code, message, details)

Validation and errors:
- Use @Valid on controllers and jakarta.validation annotations on DTOs.
- Consistent error responses via @ControllerAdvice and @ExceptionHandler.


## 9) Key Implementation Details for Redis Leaderboards

- Only-best-score per user per game:
  - ZADD lb:game:{gameId} GT NX score userId
  - If previously present and lower, ZADD will update. Use CH to know if changed.
- Top N:
  - ZREVRANGE lb:game:{gameId} 0 n-1 WITHSCORES
- Rank:
  - ZREVRANK lb:game:{gameId} userId (0-based; add 1 for 1-based rank)
- Around user:
  - rank = ZREVRANK(...)
  - start = max(0, rank - before), end = rank + after
  - ZREVRANGE start end WITHSCORES
- Global set:
  - Option 1 (simplest): maintain with ZINCRBY by delta when a user’s per-game best increases. Requires knowing the delta = newBest - oldBest for that game.
  - Option 2: recompute periodically with ZUNIONSTORE across all game sets (slower on the fly, great for batch).
- Time-windowed:
  - On submit, also ZADD to lb:game:{gameId}:daily:{yyyyMMdd} with the period aggregator rule. For “best-of-day”, use GT; for cumulative, ZINCRBY.

Serialization:
- Leaderboards store userId as member; metadata (username) should be fetched via cache/DB separately, or maintain a Redis HASH mapping userId->username for display.


## 10) Testing Strategy

Spring topics to read:
- Spring Boot test slices.
- Testcontainers (Postgres/Redis) for integration tests.

Tests to write:
- Unit tests for LeaderboardKeyFactory and utility methods.
- Integration tests for LeaderboardService with real Redis (Testcontainers):
  - submit increases best score; lower scores do nothing.
  - ranks and topN queries correct.
  - global aggregation with deltas is correct.
- Integration tests for ScoreService end-to-end (DB + Redis) confirming history and leaderboards update.
- Security tests: only authenticated users can submit; admin-only endpoints protected.
- WebSocket tests (optional): broker configuration loads; a mock publish sends to destination.

Tips:
- Use randomized UUIDs and test isolation by namespacing keys with a test prefix.
- Clean Redis keys after each test or use ephemeral containers.


## 11) Performance, Scaling, and Reliability

Topics to read:
- Redis memory sizing and eviction policies (noeviction recommended for leaderboards; handle errors gracefully).
- Redis Cluster and key hash tags if you need multi-key operations.
- Rate limiting (Bucket4j or Redis-based counters) for spammy clients.
- Caching user display info (usernames) with TTL.

Practices:
- Avoid N+1 DB lookups when enriching leaderboard entries with usernames; batch fetch.
- Consider Lua scripts for atomic read-modify-write across keys if needed.
- Monitor Redis slowlog and latency. Add metrics: counter for score submissions, gauge for top N retrieval time.


## 12) Deployment & Ops

Topics:
- Externalize configuration (env vars for DB/Redis, JWT secret/keys, CORS origins).
- Dockerize app and use Docker Compose for local Redis + Postgres.
- Health checks via Actuator /actuator/health. Liveness/readiness probes in Kubernetes.

Steps:
- Add Dockerfile (multi-stage) and optional docker-compose.yml with postgres and redis.
- Set JVM memory flags and GC logs if needed.


## 13) Class-by-Class Overview (Cheat Sheet)

Note: Method signatures are indicative. Adjust types as you code.

- package com.board.leaderboard.config
  - RedisConfig
    - RedisConnectionFactory redisConnectionFactory()
    - StringRedisTemplate stringRedisTemplate(RedisConnectionFactory)
    - RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory)
  - SecurityConfig
    - SecurityFilterChain securityFilterChain(HttpSecurity)
    - PasswordEncoder passwordEncoder()
    - AuthenticationManager authenticationManager(AuthenticationConfiguration)
  - WebSocketConfig
    - void registerStompEndpoints(StompEndpointRegistry)
    - void configureMessageBroker(MessageBrokerRegistry)

- package com.board.leaderboard.auth
  - AuthController
    - ResponseEntity<AuthResponse> register(@Valid RegisterRequest)
    - ResponseEntity<AuthResponse> login(@Valid LoginRequest)
  - AuthService
    - AuthResponse register(String username, String password)
    - AuthResponse authenticate(String username, String password)
  - JwtTokenService
    - String generateToken(UserDetails, Duration)
    - Optional<JwtClaims> parseAndValidate(String)
    - String getUsername(String)
  - JwtAuthFilter extends OncePerRequestFilter
    - void doFilterInternal(...)

- package com.board.leaderboard.user
  - UserEntity (JPA)
  - UserRepository extends JpaRepository<UserEntity, UUID>
  - Role enum

- package com.board.leaderboard.game
  - GameEntity (JPA)
  - GameRepository extends JpaRepository<GameEntity, UUID>
  - GameController (optional for admin CRUD)

- package com.board.leaderboard.score
  - ScoreHistoryEntity (JPA)
  - ScoreHistoryRepository extends JpaRepository<ScoreHistoryEntity, UUID>
  - SubmitScoreRequest, SubmitScoreResponse
  - ScoreController
    - ResponseEntity<SubmitScoreResponse> submit(@Valid SubmitScoreRequest)
    - Page<ScoreHistoryDto> history(UUID gameId, Instant from, Instant to, Pageable)
  - ScoreService
    - SubmitScoreResponse submitScore(UUID userId, UUID gameId, double score, Instant submittedAt, Optional<String> idemKey)
    - Page<ScoreHistoryDto> getUserHistory(...)

- package com.board.leaderboard.redis
  - LeaderboardKeyFactory
    - String gameLeaderboardKey(UUID gameId)
    - String globalLeaderboardKey()
    - String dailyKey(UUID gameId, LocalDate)
    - String weeklyKey(UUID gameId, YearWeek)
  - LeaderboardService
    - void submitScore(UUID userId, UUID gameId, double score, Instant submittedAt)
    - Optional<RankEntry> getUserRankInGame(UUID userId, UUID gameId)
    - List<RankEntry> getTopNInGame(UUID gameId, int n)
    - List<RankEntry> getAroundUserInGame(UUID gameId, UUID userId, int before, int after)
    - Optional<RankEntry> getUserGlobalRank(UUID userId)
    - List<RankEntry> getGlobalTopN(int n)

- package com.board.leaderboard.realtime
  - LeaderboardUpdatePublisher
    - void publishGameUpdate(UUID gameId, RankEntry entry)
    - void publishGlobalUpdate(RankEntry entry)

- package com.board.leaderboard.report
  - ReportService
    - List<RankEntry> getTopPlayersForPeriod(UUID gameId, Instant from, Instant to, int n)
  - ReportController
    - ResponseEntity<List<RankEntryDto>> top(...)

- package com.board.leaderboard.common
  - RankEntry (DTO)
  - RankEntryDto (DTO)
  - ErrorResponse
  - Custom exceptions (NotFoundException, ValidationException, UnauthorizedException)


## 14) Security, Validation, and Hardening Notes

- Protect WebSocket endpoints; require the same JWT used for REST.
- Rate-limit score submissions per user/IP.
- Validate inputs strictly; score should be within sane ranges; reject NaN/Infinity.
- Sanitize outputs; never leak password hashes or tokens.
- Use HTTPS in production; set SameSite/secure flags if using cookies.
- Log security events cautiously (avoid sensitive data).


## 15) Development Tips

- Start with a minimal happy path: register -> login -> submit score -> see top N.
- Use Postman/HTTPie to test endpoints as you build them.
- Keep feature flags or simple config toggles to switch aggregation strategies (best vs cumulative).
- Add sample data command-line runner (ApplicationRunner) in dev profile.
- Document your Redis key format in code comments and this file; consistency prevents bugs.


## 16) Milestone Checklist

- M1: Auth working with JWT; create/list games. (SecurityConfig, AuthController, GameController)
- M2: Score submission persists history; per-game leaderboard updated in Redis. (ScoreService, LeaderboardService)
- M3: Global leaderboard aggregations. (delta-based or periodic rebuild)
- M4: WebSocket real-time updates. (LeaderboardUpdatePublisher + WS config)
- M5: Reports for a date range using Redis union or DB aggregate. (ReportService)
- M6: Hardening, tests (Testcontainers), and observability (Actuator).


## Appendix A: Example Redis Command Flows

- First submission (best-of):
  - ZADD lb:game:{gameId} GT NX <score> <userId>
  - dailyKey = lb:game:{gameId}:daily:{yyyyMMdd}; ZADD dailyKey GT NX <score> <userId>
  - If increased best, compute delta = newBest - oldBest and ZINCRBY lb:global <delta> <userId>

- Get top 10 for game:
  - ZREVRANGE lb:game:{gameId} 0 9 WITHSCORES

- Get rank for user:
  - rank = ZREVRANK lb:game:{gameId} <userId>  (0-based)
  - score = ZSCORE lb:game:{gameId} <userId>


## Appendix B: Sample Error Cases to Handle

- Submitting score for non-existent game -> 404.
- Submitting negative/overflow score -> 400.
- Submitting a lower score when using "best-of" -> still 200 but no change; include a flag changed=false.
- Unauthorized access to protected endpoints -> 401.
- Too many requests -> 429 (rate limiter to be added later).


## Appendix C: Configuration Hints (application.properties)

- spring.datasource.url, username, password
- spring.jpa.hibernate.ddl-auto=validate (use Flyway)
- spring.flyway.enabled=true
- spring.redis.host, port
- app.jwt.secret, app.jwt.expiration
- logging.level.org.springframework=INFO


You’re ready to start. Use this roadmap as a living document—update it as your understanding grows and your requirements evolve.