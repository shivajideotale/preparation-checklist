# 🔐 JWT — Deep Dive with Spring Boot

> A comprehensive guide to JSON Web Tokens: the theory, internals, security best practices, and full Spring Boot implementation — from scratch to production-ready.

---

## 📋 Table of Contents

- [What is JWT?](#what-is-jwt)
- [JWT Structure — Deep Dive](#jwt-structure--deep-dive)
- [JWT Algorithms](#jwt-algorithms)
- [JWT Lifecycle](#jwt-lifecycle)
- [Access Token vs Refresh Token](#access-token-vs-refresh-token)
- [Spring Boot JWT — Project Setup](#spring-boot-jwt--project-setup)
- [Project Structure](#project-structure)
- [Dependencies](#dependencies)
- [JWT Utility Service](#jwt-utility-service)
- [User Details & Authentication](#user-details--authentication)
- [JWT Filter — Request Interception](#jwt-filter--request-interception)
- [Security Configuration](#security-configuration)
- [Auth Controller — Login & Register](#auth-controller--login--register)
- [Refresh Token Implementation](#refresh-token-implementation)
- [Role-Based Access Control (RBAC)](#role-based-access-control-rbac)
- [Token Blacklisting & Revocation](#token-blacklisting--revocation)
- [JWT in Microservices](#jwt-in-microservices)
- [Security Best Practices](#security-best-practices)
- [Common Vulnerabilities & Fixes](#common-vulnerabilities--fixes)
- [Testing JWT](#testing-jwt)
- [Summary](#summary)

---

## What is JWT?

**JWT (JSON Web Token)** is an open standard (**RFC 7519**) that defines a compact, self-contained way to securely transmit information between parties as a JSON object. The information is digitally **signed** — so it can be **verified** and **trusted**.

### Why JWT Over Sessions?

| Feature | Session-Based Auth | JWT-Based Auth |
|---|---|---|
| **Storage** | Server stores session | Stateless — no server storage |
| **Scalability** | Hard (sticky sessions or shared cache) | Easy (any server validates token) |
| **Microservices** | Requires shared session store | Token is self-contained |
| **Mobile-friendly** | Cookies are tricky on mobile | Bearer tokens work everywhere |
| **Revocation** | Easy (delete session) | Harder (needs blacklist) |
| **Payload** | Server holds all data | Claims embedded in token |
| **Cross-domain** | CORS cookie issues | Works across domains |

---

## JWT Structure — Deep Dive

A JWT consists of **three Base64URL-encoded parts** separated by dots:

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
.
eyJzdWIiOiJ1c2VyLTEyMyIsInJvbGVzIjpbIlJPTEVfVVNFUiJdLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MTcwMDAwMzYwMH0
.
SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c

└──────── Header ────────┘ └──────────────── Payload ──────────────────┘ └── Signature ──┘
```

### Part 1 — Header

Describes the token type and signing algorithm.

```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

Base64URL encoded → `eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9`

---

### Part 2 — Payload (Claims)

Contains the **claims** — statements about the user and metadata.

```json
{
  "sub": "user-123",
  "email": "john@example.com",
  "roles": ["ROLE_USER", "ROLE_ADMIN"],
  "iat": 1700000000,
  "exp": 1700003600,
  "iss": "https://auth.myapp.com",
  "aud": "https://api.myapp.com",
  "jti": "a8098c1a-f86e-11da-bd1a"
}
```

#### Claim Types

**Registered Claims (Standard — RFC 7519):**

| Claim | Full Name | Description |
|---|---|---|
| `sub` | Subject | Unique user identifier |
| `iss` | Issuer | Who issued the token (your auth server URL) |
| `aud` | Audience | Intended recipient (your API URL) |
| `exp` | Expiration | Unix timestamp when token expires |
| `iat` | Issued At | Unix timestamp when token was issued |
| `nbf` | Not Before | Token not valid before this timestamp |
| `jti` | JWT ID | Unique token ID (used for revocation) |

**Public Claims:** Custom claims registered in the IANA registry.

**Private Claims:** Custom claims agreed upon by your system.

```json
{
  "sub": "user-123",
  "tenantId": "tenant-abc",
  "plan": "premium",
  "permissions": ["orders:read", "orders:write", "reports:read"]
}
```

> ⚠️ **Important:** JWT payload is Base64URL encoded — NOT encrypted. Anyone can decode and read it. Never store sensitive data (passwords, SSN, card numbers) in JWT claims.

---

### Part 3 — Signature

The signature ensures the token hasn't been tampered with.

```
HMACSHA256(
  base64UrlEncode(header) + "." + base64UrlEncode(payload),
  secret_key
)
```

- If any part of the header or payload changes → signature becomes invalid
- Only parties with the **secret key** (HMAC) or **private key** (RSA/ECDSA) can create valid tokens
- Any party with the **public key** (RSA/ECDSA) or **secret key** (HMAC) can verify tokens

---

## JWT Algorithms

### HMAC (Symmetric) — HS256, HS384, HS512

```
One secret key used for BOTH signing and verification.

Auth Server:  signs token with secret_key
API Server:   verifies token with same secret_key

✅ Simple, fast
✅ Good for single-server or trusted internal services
❌ Secret must be shared — risk if leaked
❌ All services that verify must hold the secret
```

### RSA (Asymmetric) — RS256, RS384, RS512

```
Private key → used ONLY by auth server to sign
Public key  → shared freely, used by anyone to verify

Auth Server:  signs with private_key (never shared)
API Servers:  verify with public_key (safe to distribute)

✅ Private key never leaves auth server
✅ Any service can verify without knowing the secret
✅ Best for microservices and multi-service architectures
❌ Slower than HMAC (RSA operations are expensive)
```

### ECDSA (Elliptic Curve) — ES256, ES384, ES512

```
Like RSA but uses elliptic curve cryptography.

✅ Smaller keys than RSA (256-bit EC ≈ 3072-bit RSA security)
✅ Faster than RSA
✅ Smaller token size
✅ Recommended for modern systems
```

### Algorithm Comparison

| Algorithm | Type | Key Size | Speed | Token Size | Use Case |
|---|---|---|---|---|---|
| **HS256** | Symmetric | 256-bit | ⚡⚡⚡ | Small | Monolith, trusted services |
| **HS512** | Symmetric | 512-bit | ⚡⚡ | Small | Higher security monolith |
| **RS256** | Asymmetric | 2048-bit | ⚡ | Large | Microservices, OAuth2 |
| **ES256** | Asymmetric | 256-bit | ⚡⚡ | Medium | Modern microservices |

> 🔒 **Recommendation:** Use **RS256** or **ES256** for production microservices. Use **HS256** only for simple single-service apps.

---

## JWT Lifecycle

```
┌─────────────────────────────────────────────────────────────────────┐
│                      JWT LIFECYCLE                                   │
│                                                                     │
│  1. LOGIN                                                           │
│     Client ──POST /auth/login──→ Auth Server                        │
│             { email, password }                                     │
│             ←── { accessToken, refreshToken } ──                    │
│                                                                     │
│  2. ACCESS RESOURCE                                                 │
│     Client ──GET /api/orders──→ API Server                          │
│             Authorization: Bearer <accessToken>                     │
│             ←── { orders data } ──                                  │
│                                                                     │
│  3. TOKEN EXPIRED (401 Unauthorized)                                │
│     Client ──GET /api/orders──→ API Server                          │
│             ←── 401 Token Expired ──                                │
│                                                                     │
│  4. REFRESH                                                         │
│     Client ──POST /auth/refresh──→ Auth Server                      │
│             { refreshToken }                                        │
│             ←── { newAccessToken } ──                               │
│                                                                     │
│  5. LOGOUT                                                          │
│     Client ──POST /auth/logout──→ Auth Server                       │
│             { refreshToken }                                        │
│             ←── refreshToken invalidated ──                         │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Access Token vs Refresh Token

| Property | Access Token | Refresh Token |
|---|---|---|
| **Purpose** | Authorize API requests | Obtain new access tokens |
| **Lifespan** | Short: 15 min – 1 hour | Long: 7 days – 30 days |
| **Storage** | Memory (JS) / Secure storage (mobile) | HttpOnly cookie or secure storage |
| **Sent to** | Every API request (Authorization header) | Only to /auth/refresh endpoint |
| **Revocable** | Hard (stateless) | Yes (stored in DB, can be deleted) |
| **Compromise risk** | Low (short-lived) | Higher (long-lived, must be stored) |
| **Contains** | User claims, roles, permissions | Minimal (just enough to identify user) |

```
Access Token lifespan:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
|   15 min   |   15 min   |   15 min   |   15 min   |
T=0         T=15         T=30         T=45          T=60 (expired)

Refresh Token covers the full window, refreshing access tokens silently:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
|                        7 days                                     |
T=0                                                              T=7days
```

---

## Spring Boot JWT — Project Setup

### Prerequisites

- Java 17+
- Spring Boot 3.x
- Maven or Gradle
- PostgreSQL (for user and refresh token storage)

---

## Project Structure

```
src/
├── main/
│   ├── java/com/example/jwtdemo/
│   │   ├── JwtDemoApplication.java
│   │   ├── config/
│   │   │   └── SecurityConfig.java
│   │   ├── controller/
│   │   │   ├── AuthController.java
│   │   │   └── UserController.java
│   │   ├── dto/
│   │   │   ├── LoginRequest.java
│   │   │   ├── RegisterRequest.java
│   │   │   ├── AuthResponse.java
│   │   │   └── RefreshTokenRequest.java
│   │   ├── entity/
│   │   │   ├── User.java
│   │   │   └── RefreshToken.java
│   │   ├── exception/
│   │   │   ├── GlobalExceptionHandler.java
│   │   │   └── TokenExpiredException.java
│   │   ├── filter/
│   │   │   └── JwtAuthenticationFilter.java
│   │   ├── repository/
│   │   │   ├── UserRepository.java
│   │   │   └── RefreshTokenRepository.java
│   │   └── service/
│   │       ├── JwtService.java
│   │       ├── AuthService.java
│   │       ├── RefreshTokenService.java
│   │       └── CustomUserDetailsService.java
│   └── resources/
│       └── application.yml
```

---

## Dependencies

### pom.xml

```xml
<dependencies>

    <!-- Spring Boot Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Spring Security -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- Spring Data JPA -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- PostgreSQL Driver -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- JJWT — JWT Library -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.3</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>

    <!-- Validation -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Redis (for token blacklisting) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <!-- Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-test</artifactId>
        <scope>test</scope>
    </dependency>

</dependencies>
```

### application.yml

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/jwtdemo
    username: postgres
    password: postgres
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  data:
    redis:
      host: localhost
      port: 6379

application:
  security:
    jwt:
      secret-key: 404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970
      access-token-expiration: 900000         # 15 minutes in ms
      refresh-token-expiration: 604800000     # 7 days in ms
      issuer: https://auth.myapp.com
      audience: https://api.myapp.com
```

---

## Entity Classes

### User.java

```java
package com.example.jwtdemo.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.*;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    private Set<Role> roles = new HashSet<>();

    private boolean enabled = true;
    private boolean accountNonLocked = true;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
            .map(role -> new SimpleGrantedAuthority(role.name()))
            .toList();
    }

    @Override
    public String getUsername() { return email; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return accountNonLocked; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return enabled; }
}
```

### Role.java (Enum)

```java
package com.example.jwtdemo.entity;

public enum Role {
    ROLE_USER,
    ROLE_ADMIN,
    ROLE_MODERATOR
}
```

### RefreshToken.java

```java
package com.example.jwtdemo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Instant expiresAt;

    private boolean revoked = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // Device info for security tracking
    private String deviceInfo;
    private String ipAddress;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }
}
```

---

## JWT Utility Service

### JwtService.java

```java
package com.example.jwtdemo.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.*;
import java.util.function.Function;

@Service
@Slf4j
public class JwtService {

    @Value("${application.security.jwt.secret-key}")
    private String secretKey;

    @Value("${application.security.jwt.access-token-expiration}")
    private long accessTokenExpiration;

    @Value("${application.security.jwt.issuer}")
    private String issuer;

    @Value("${application.security.jwt.audience}")
    private String audience;

    // ─── Token Generation ──────────────────────────────────────────────────────

    public String generateAccessToken(UserDetails userDetails) {
        Map<String, Object> extraClaims = new HashMap<>();
        extraClaims.put("roles", userDetails.getAuthorities()
            .stream()
            .map(GrantedAuthority::getAuthority)
            .toList());
        extraClaims.put("type", "access");
        return buildToken(extraClaims, userDetails, accessTokenExpiration);
    }

    public String generateAccessToken(UserDetails userDetails, Map<String, Object> extraClaims) {
        extraClaims.put("type", "access");
        return buildToken(extraClaims, userDetails, accessTokenExpiration);
    }

    private String buildToken(Map<String, Object> extraClaims, UserDetails userDetails, long expiration) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
            .claims(extraClaims)
            .subject(userDetails.getUsername())
            .issuer(issuer)
            .audience().add(audience).and()
            .issuedAt(now)
            .notBefore(now)
            .expiration(expiryDate)
            .id(UUID.randomUUID().toString())      // jti — unique token ID
            .signWith(getSigningKey(), Jwts.SIG.HS256)
            .compact();
    }

    // ─── Token Validation ──────────────────────────────────────────────────────

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    public boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    // ─── Claims Extraction ─────────────────────────────────────────────────────

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public String extractTokenId(String token) {
        return extractClaim(token, Claims::getId);
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        return extractClaim(token, claims -> claims.get("roles", List.class));
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .requireIssuer(issuer)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    // ─── Utilities ─────────────────────────────────────────────────────────────

    public long getExpirationTime(String token) {
        Date expiration = extractExpiration(token);
        return expiration.getTime() - System.currentTimeMillis();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
```

---

## User Details & Authentication

### CustomUserDetailsService.java

```java
package com.example.jwtdemo.service;

import com.example.jwtdemo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException(
                "User not found with email: " + email
            ));
    }
}
```

### UserRepository.java

```java
package com.example.jwtdemo.repository;

import com.example.jwtdemo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

---

## JWT Filter — Request Interception

### JwtAuthenticationFilter.java

```java
package com.example.jwtdemo.filter;

import com.example.jwtdemo.service.*;
import io.jsonwebtoken.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. Skip filter for public endpoints
        if (shouldSkipFilter(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Extract Authorization header
        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. Extract token (remove "Bearer " prefix)
        final String jwt = authHeader.substring(7);

        try {
            // 4. Check token blacklist (for logged-out tokens)
            if (tokenBlacklistService.isBlacklisted(jwt)) {
                log.warn("Blacklisted token used from IP: {}", request.getRemoteAddr());
                sendUnauthorizedError(response, "Token has been revoked");
                return;
            }

            // 5. Extract username from token
            final String userEmail = jwtService.extractUsername(jwt);

            // 6. If username found and no authentication in context yet
            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // 7. Load user details
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

                // 8. Validate token
                if (jwtService.isTokenValid(jwt, userDetails)) {

                    // 9. Create authentication token
                    UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                        );

                    // 10. Add request details (IP, session ID)
                    authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                    );

                    // 11. Set authentication in Security Context
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    log.debug("Authenticated user: {}", userEmail);
                }
            }

        } catch (ExpiredJwtException e) {
            log.info("Expired JWT token for request: {}", request.getRequestURI());
            sendUnauthorizedError(response, "Token has expired");
            return;
        } catch (MalformedJwtException e) {
            log.warn("Malformed JWT token from IP: {}", request.getRemoteAddr());
            sendUnauthorizedError(response, "Invalid token format");
            return;
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            sendUnauthorizedError(response, "Token validation failed");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean shouldSkipFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/auth/") ||
               path.startsWith("/actuator/health") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/v3/api-docs");
    }

    private void sendUnauthorizedError(HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
            String.format("{\"error\": \"Unauthorized\", \"message\": \"%s\"}", message)
        );
    }
}
```

---

## Security Configuration

### SecurityConfig.java

```java
package com.example.jwtdemo.config;

import com.example.jwtdemo.filter.JwtAuthenticationFilter;
import com.example.jwtdemo.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity                          // enables @PreAuthorize, @Secured
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthEntryPoint jwtAuthEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            // Disable CSRF — using JWT (stateless), CSRF not needed
            .csrf(AbstractHttpConfigurer::disable)

            // Configure exception handling
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(jwtAuthEntryPoint)
            )

            // Stateless session — no session created or used
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Configure authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // Role-based access
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/moderator/**").hasAnyRole("ADMIN", "MODERATOR")

                // All other requests require authentication
                .anyRequest().authenticated()
            )

            // Add JWT filter before Spring's UsernamePasswordAuthenticationFilter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

            .build();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);   // strength 12 = secure default
    }
}
```

### JwtAuthEntryPoint.java

```java
package com.example.jwtdemo.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JwtAuthEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("""
            {
                "error": "Unauthorized",
                "message": "Authentication required to access this resource",
                "path": "%s"
            }
            """.formatted(request.getRequestURI()));
    }
}
```

---

## Auth Controller — Login & Register

### DTOs

```java
// LoginRequest.java
public record LoginRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password
) {}

// RegisterRequest.java
public record RegisterRequest(
    @NotBlank String fullName,
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password
) {}

// AuthResponse.java
public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    String userId,
    String email,
    List<String> roles
) {
    public static AuthResponse of(String accessToken, String refreshToken,
                                   long expiresIn, User user) {
        return new AuthResponse(
            accessToken, refreshToken, "Bearer", expiresIn,
            user.getId(), user.getEmail(),
            user.getRoles().stream().map(Enum::name).toList()
        );
    }
}

// RefreshTokenRequest.java
public record RefreshTokenRequest(
    @NotBlank String refreshToken
) {}
```

### AuthService.java

```java
package com.example.jwtdemo.service;

import com.example.jwtdemo.dto.*;
import com.example.jwtdemo.entity.*;
import com.example.jwtdemo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuthenticationManager authenticationManager;
    private final TokenBlacklistService tokenBlacklistService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check if email already exists
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email already registered: " + request.email());
        }

        // Create user
        User user = User.builder()
            .fullName(request.fullName())
            .email(request.email())
            .password(passwordEncoder.encode(request.password()))
            .roles(Set.of(Role.ROLE_USER))
            .build();

        user = userRepository.save(user);
        log.info("New user registered: {}", user.getEmail());

        // Generate tokens
        String accessToken = jwtService.generateAccessToken(user);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);

        return AuthResponse.of(
            accessToken,
            refreshToken.getToken(),
            jwtService.getExpirationTime(accessToken),
            user
        );
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress, String deviceInfo) {
        try {
            // Spring Security authenticates credentials
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
            );
        } catch (AuthenticationException e) {
            log.warn("Failed login attempt for email: {} from IP: {}", request.email(), ipAddress);
            throw new BadCredentialsException("Invalid email or password");
        }

        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        // Revoke existing refresh tokens for this device (optional)
        refreshTokenService.revokeAllUserTokens(user);

        // Generate new tokens
        String accessToken = jwtService.generateAccessToken(user);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user, ipAddress, deviceInfo);

        log.info("User logged in: {} from IP: {}", user.getEmail(), ipAddress);

        return AuthResponse.of(
            accessToken,
            refreshToken.getToken(),
            jwtService.getExpirationTime(accessToken),
            user
        );
    }

    @Transactional
    public AuthResponse refreshToken(String refreshTokenValue) {
        return refreshTokenService.findByToken(refreshTokenValue)
            .map(refreshTokenService::verifyExpiration)
            .map(refreshToken -> {
                User user = refreshToken.getUser();
                String newAccessToken = jwtService.generateAccessToken(user);
                log.debug("Access token refreshed for user: {}", user.getEmail());
                return AuthResponse.of(
                    newAccessToken,
                    refreshTokenValue,    // return same refresh token
                    jwtService.getExpirationTime(newAccessToken),
                    user
                );
            })
            .orElseThrow(() -> new TokenRefreshException("Refresh token not found or invalid"));
    }

    @Transactional
    public void logout(String accessToken, String refreshTokenValue) {
        // 1. Blacklist the access token until it expires naturally
        long ttl = jwtService.getExpirationTime(accessToken);
        if (ttl > 0) {
            tokenBlacklistService.blacklist(accessToken, ttl);
        }

        // 2. Revoke refresh token from DB
        refreshTokenService.revokeToken(refreshTokenValue);

        log.info("User logged out — tokens invalidated");
    }
}
```

### AuthController.java

```java
package com.example.jwtdemo.controller;

import com.example.jwtdemo.dto.*;
import com.example.jwtdemo.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        String ipAddress = getClientIp(httpRequest);
        String deviceInfo = httpRequest.getHeader("User-Agent");

        AuthResponse response = authService.login(request, ipAddress, deviceInfo);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request.refreshToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody RefreshTokenRequest request) {

        String accessToken = authHeader.substring(7);
        authService.logout(accessToken, request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

---

## Refresh Token Implementation

### RefreshTokenService.java

```java
package com.example.jwtdemo.service;

import com.example.jwtdemo.entity.*;
import com.example.jwtdemo.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${application.security.jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    public RefreshToken createRefreshToken(User user) {
        return createRefreshToken(user, null, null);
    }

    public RefreshToken createRefreshToken(User user, String ipAddress, String deviceInfo) {
        RefreshToken token = RefreshToken.builder()
            .user(user)
            .token(UUID.randomUUID().toString())
            .expiresAt(Instant.now().plusMillis(refreshTokenExpiration))
            .ipAddress(ipAddress)
            .deviceInfo(deviceInfo)
            .revoked(false)
            .build();
        return refreshTokenRepository.save(token);
    }

    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.isExpired()) {
            refreshTokenRepository.delete(token);
            throw new TokenRefreshException("Refresh token has expired. Please log in again.");
        }
        if (token.isRevoked()) {
            throw new TokenRefreshException("Refresh token has been revoked. Please log in again.");
        }
        return token;
    }

    @Transactional
    public void revokeToken(String tokenValue) {
        refreshTokenRepository.findByToken(tokenValue)
            .ifPresent(token -> {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            });
    }

    @Transactional
    public void revokeAllUserTokens(User user) {
        refreshTokenRepository.revokeAllByUser(user);
    }

    @Transactional
    public void deleteExpiredTokens() {
        refreshTokenRepository.deleteAllExpiredTokens(Instant.now());
    }
}
```

### RefreshTokenRepository.java

```java
package com.example.jwtdemo.repository;

import com.example.jwtdemo.entity.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user = :user AND r.revoked = false")
    void revokeAllByUser(@Param("user") User user);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :now OR r.revoked = true")
    void deleteAllExpiredTokens(@Param("now") Instant now);
}
```

---

## Token Blacklisting & Revocation

### TokenBlacklistService.java (Redis-backed)

```java
package com.example.jwtdemo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final StringRedisTemplate redisTemplate;
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    // Blacklist token until it would have naturally expired
    public void blacklist(String token, long ttlMillis) {
        String key = BLACKLIST_PREFIX + token;
        redisTemplate.opsForValue().set(key, "revoked", ttlMillis, TimeUnit.MILLISECONDS);
    }

    // Check if token is blacklisted
    public boolean isBlacklisted(String token) {
        String key = BLACKLIST_PREFIX + token;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
```

> **Why Redis?** Blacklisted tokens must expire automatically when the JWT would have expired. Redis TTL handles this perfectly — no manual cleanup needed.

---

## Role-Based Access Control (RBAC)

### Method-Level Security with @PreAuthorize

```java
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    // Any authenticated user can access their own profile
    @GetMapping("/profile")
    public UserProfileView getProfile(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return UserProfileView.from(user);
    }

    // Only ADMINs can list all users
    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserSummaryView> getAllUsers() {
        return userRepository.findAll()
            .stream().map(UserSummaryView::from).toList();
    }

    // User can access their own data, ADMIN can access any
    @GetMapping("/users/{userId}")
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
    public UserProfileView getUserById(@PathVariable String userId) {
        return userRepository.findById(userId)
            .map(UserProfileView::from)
            .orElseThrow(() -> new EntityNotFoundException("User not found"));
    }

    // Only ADMIN or MODERATOR
    @DeleteMapping("/admin/users/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Void> deleteUser(@PathVariable String userId) {
        userRepository.deleteById(userId);
        return ResponseEntity.noContent().build();
    }

    // Permission-based (custom claim in JWT)
    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('reports:read')")
    public ReportView getReports() {
        // ...
    }
}
```

### Custom Permission Evaluator

```java
@Component
@RequiredArgsConstructor
public class CustomPermissionEvaluator implements PermissionEvaluator {

    private final OrderRepository orderRepository;

    @Override
    public boolean hasPermission(Authentication auth, Object targetDomainObject, Object permission) {
        if (auth == null || !auth.isAuthenticated()) return false;
        if (targetDomainObject instanceof Order order) {
            String userId = ((User) auth.getPrincipal()).getId();
            return switch (permission.toString()) {
                case "READ"   -> order.getCustomerId().equals(userId) || hasRole(auth, "ADMIN");
                case "CANCEL" -> order.getCustomerId().equals(userId) && order.isCancellable();
                default       -> false;
            };
        }
        return false;
    }

    @Override
    public boolean hasPermission(Authentication auth, Serializable targetId,
                                  String targetType, Object permission) {
        if ("Order".equals(targetType)) {
            return orderRepository.findById(targetId.toString())
                .map(order -> hasPermission(auth, order, permission))
                .orElse(false);
        }
        return false;
    }

    private boolean hasRole(Authentication auth, String role) {
        return auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
}

// Usage:
@PreAuthorize("hasPermission(#orderId, 'Order', 'CANCEL')")
public void cancelOrder(@PathVariable String orderId) { ... }
```

---

## JWT in Microservices

### Token Propagation Between Services

```
Client → API Gateway (validates JWT) → Order Service
                                      → passes JWT in header
Order Service → Payment Service (JWT forwarded)
             → validates same JWT
             → extracts userId from claims directly (no DB call)
```

### Service-to-Service JWT (Machine-to-Machine)

```java
// Internal service token generation (no user involved)
public String generateServiceToken(String serviceId, String targetService) {
    return Jwts.builder()
        .subject(serviceId)
        .claim("type", "service")
        .claim("target", targetService)
        .issuer("internal-auth")
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + 60_000))  // 1 min
        .signWith(getSigningKey())
        .compact();
}

// Feign client interceptor — auto-attaches token
@Bean
public RequestInterceptor jwtRequestInterceptor() {
    return template -> {
        String token = serviceTokenService.getCurrentToken();
        template.header("Authorization", "Bearer " + token);
    };
}
```

### JWKS Endpoint (Public Key Distribution for RS256)

```java
// Auth server exposes public keys — other services fetch and cache them
@GetMapping("/.well-known/jwks.json")
public JwksResponse getJwks() {
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    return JwksResponse.from(publicKey);
}

// Other services auto-fetch and verify:
// spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://auth-service/.well-known/jwks.json
```

---

## Security Best Practices

### Token Storage

```
Browser:
  ✅ Access token: in-memory JavaScript variable (NOT localStorage)
  ✅ Refresh token: HttpOnly, Secure, SameSite=Strict cookie

  ❌ Never store tokens in localStorage → vulnerable to XSS
  ❌ Never store tokens in sessionStorage → still XSS vulnerable

Mobile (iOS/Android):
  ✅ Keychain (iOS) / Keystore (Android) — hardware-backed secure storage
  ✅ Never in SharedPreferences (Android) or UserDefaults (iOS)
```

### Secret Key Requirements

```java
// ✅ Correct: Generate a strong key (at least 256 bits for HS256)
SecretKey key = Keys.secretKeyFor(SignatureAlgorithm.HS256);
String base64Key = Encoders.BASE64.encode(key.getEncoded());
// Store this in application.yml / Vault — never in code

// ❌ Wrong: Weak, guessable secrets
"secret"
"mysecretkey"
"jwt_secret_123"

// Minimum key lengths:
// HS256: 256 bits (32 bytes)
// HS384: 384 bits (48 bytes)
// HS512: 512 bits (64 bytes)
// RS256: 2048-bit RSA key
```

### Security Checklist

```
JWT Generation:
  ✅ Use strong algorithm (HS256+, RS256, ES256)
  ✅ Include exp claim (always set expiration)
  ✅ Include iss claim (issuer)
  ✅ Include aud claim (audience) and validate it
  ✅ Include jti claim (unique ID for revocation)
  ✅ Short access token expiry (15 min recommended)
  ✅ Never include sensitive data in payload

JWT Validation:
  ✅ Validate signature
  ✅ Validate expiration (exp)
  ✅ Validate issuer (iss)
  ✅ Validate audience (aud)
  ✅ Validate not-before (nbf)
  ✅ Check blacklist for logged-out tokens
  ✅ Explicitly specify allowed algorithms (prevent alg:none attack)

Infrastructure:
  ✅ HTTPS only — never send JWT over plain HTTP
  ✅ Rotate signing keys periodically
  ✅ Store secrets in Vault / Secrets Manager
  ✅ Log failed validation attempts
  ✅ Rate-limit /auth/login endpoint
```

---

## Common Vulnerabilities & Fixes

### Vulnerability 1 — Algorithm Confusion (alg: none)

```java
// ❌ Vulnerable: trusts algorithm from token header
Jwts.parser().parseSignedClaims(token);  // old insecure approach

// ✅ Fixed: explicitly specify expected algorithm
Jwts.parser()
    .verifyWith(getSigningKey())          // JJWT 0.12+ handles this correctly
    .build()
    .parseSignedClaims(token);
```

### Vulnerability 2 — RS256 → HS256 Confusion

```
Attack: Server uses RS256, attacker knows public key.
        Attacker signs token with HS256 using public key as secret.
        Vulnerable server: "oh, it's HS256, let me verify with... public key" → passes!

Fix:    Explicitly verify the algorithm claim matches expected:
        assert claims.getHeader().getAlgorithm().equals("RS256");
```

### Vulnerability 3 — Missing Expiration

```java
// ❌ Vulnerable: no expiry — token valid forever
Jwts.builder().subject("user-123").signWith(key).compact();

// ✅ Fixed: always set expiration
Jwts.builder()
    .subject("user-123")
    .expiration(new Date(System.currentTimeMillis() + 900_000))  // 15 min
    .signWith(key)
    .compact();
```

### Vulnerability 4 — JWT in URL

```
❌ Vulnerable:
GET /api/orders?token=eyJhbGciOiJIUzI1NiJ9...
→ Token logged in server access logs
→ Token in browser history
→ Token in Referer headers

✅ Fixed: Always use Authorization header:
GET /api/orders
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

### Vulnerability 5 — Not Validating Audience

```java
// ❌ Vulnerable: token for service A accepted by service B
Jwts.parser().verifyWith(key).build().parseSignedClaims(token);

// ✅ Fixed: validate audience
Jwts.parser()
    .verifyWith(key)
    .requireAudience("https://api.myapp.com")
    .requireIssuer("https://auth.myapp.com")
    .build()
    .parseSignedClaims(token);
```

---

## Testing JWT

### Unit Testing JwtService

```java
@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    @InjectMocks
    private JwtService jwtService;

    private User testUser;

    @BeforeEach
    void setup() {
        // Inject test values
        ReflectionTestUtils.setField(jwtService, "secretKey",
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 900000L);
        ReflectionTestUtils.setField(jwtService, "issuer", "test-issuer");
        ReflectionTestUtils.setField(jwtService, "audience", "test-audience");

        testUser = User.builder()
            .id("user-123")
            .email("test@example.com")
            .password("encoded_password")
            .roles(Set.of(Role.ROLE_USER))
            .build();
    }

    @Test
    void generateAccessToken_ShouldReturnValidToken() {
        String token = jwtService.generateAccessToken(testUser);
        assertThat(token).isNotNull().isNotEmpty();
    }

    @Test
    void extractUsername_ShouldReturnCorrectEmail() {
        String token = jwtService.generateAccessToken(testUser);
        String username = jwtService.extractUsername(token);
        assertThat(username).isEqualTo("test@example.com");
    }

    @Test
    void isTokenValid_WithValidToken_ShouldReturnTrue() {
        String token = jwtService.generateAccessToken(testUser);
        assertThat(jwtService.isTokenValid(token, testUser)).isTrue();
    }

    @Test
    void isTokenExpired_WithExpiredToken_ShouldReturnTrue() {
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", -1000L);
        String token = jwtService.generateAccessToken(testUser);
        assertThat(jwtService.isTokenExpired(token)).isTrue();
    }
}
```

### Integration Testing Auth Endpoints

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setup() {
        User user = User.builder()
            .email("test@example.com")
            .password(passwordEncoder.encode("Password1!"))
            .fullName("Test User")
            .roles(Set.of(Role.ROLE_USER))
            .build();
        userRepository.save(user);
    }

    @Test
    void login_WithValidCredentials_ShouldReturnTokens() throws Exception {
        LoginRequest request = new LoginRequest("test@example.com", "Password1!");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void login_WithWrongPassword_ShouldReturn401() throws Exception {
        LoginRequest request = new LoginRequest("test@example.com", "WrongPassword!");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_WithValidToken_ShouldReturn200() throws Exception {
        // Login to get token
        String token = loginAndGetToken();

        // Access protected endpoint
        mockMvc.perform(get("/api/profile")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_WithoutToken_ShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/profile"))
            .andExpect(status().isUnauthorized());
    }

    private String loginAndGetToken() throws Exception {
        LoginRequest request = new LoginRequest("test@example.com", "Password1!");
        String responseBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).get("accessToken").asText();
    }
}
```

---

## API Reference

### Authentication Endpoints

| Method | Endpoint | Auth Required | Description |
|---|---|---|---|
| `POST` | `/api/auth/register` | No | Register new user |
| `POST` | `/api/auth/login` | No | Login, get tokens |
| `POST` | `/api/auth/refresh` | No | Refresh access token |
| `POST` | `/api/auth/logout` | Yes | Invalidate tokens |

### Request / Response Examples

```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"John Doe","email":"john@example.com","password":"Password1!"}'

# Response:
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "tokenType": "Bearer",
  "expiresIn": 899843,
  "userId": "a1b2c3d4-...",
  "email": "john@example.com",
  "roles": ["ROLE_USER"]
}

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"john@example.com","password":"Password1!"}'

# Access protected resource
curl -X GET http://localhost:8080/api/profile \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."

# Refresh token
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"550e8400-e29b-41d4-a716-446655440000"}'

# Logout
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"550e8400-e29b-41d4-a716-446655440000"}'
```

---

## Summary

```
JWT Flow in Spring Boot:

 Client                    Spring Boot App                       DB / Redis
   │                            │                                    │
   │──POST /auth/login──────────→│                                    │
   │   { email, password }       │──validate credentials──────────────→│
   │                             │──generate accessToken + refreshToken│
   │                             │──save refreshToken─────────────────→│
   │←──{ accessToken,            │                                    │
   │     refreshToken }──────────│                                    │
   │                             │                                    │
   │──GET /api/orders────────────→│                                    │
   │  Authorization: Bearer xxx  │                                    │
   │                             │ JwtAuthenticationFilter:           │
   │                             │  1. Extract token from header      │
   │                             │  2. Check blacklist (Redis)        │
   │                             │  3. Validate signature + expiry    │
   │                             │  4. Set SecurityContext            │
   │                             │  5. Pass to controller             │
   │←──{ orders data }───────────│                                    │
   │                             │                                    │
   │──POST /auth/refresh─────────→│                                    │
   │  { refreshToken }           │──find refreshToken─────────────────→│
   │                             │──verify not expired/revoked        │
   │                             │──generate new accessToken          │
   │←──{ newAccessToken }────────│                                    │
   │                             │                                    │
   │──POST /auth/logout──────────→│                                    │
   │  Bearer + refreshToken      │──blacklist accessToken (Redis)─────→│
   │                             │──revoke refreshToken (DB)──────────→│
   │←──204 No Content────────────│                                    │

Key Principle:
  Access Token  = short-lived (15min), stateless, validated by signature alone
  Refresh Token = long-lived (7d), stateful, stored in DB, can be revoked anytime
```

---

## 📚 Further Reading

- [RFC 7519 — JSON Web Token](https://tools.ietf.org/html/rfc7519)
- [RFC 7517 — JSON Web Key (JWK)](https://tools.ietf.org/html/rfc7517)
- [OWASP JWT Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html)
- [JWT.io — Debugger & Libraries](https://jwt.io/)
- [JJWT Library Documentation](https://github.com/jwtk/jjwt)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- [Auth0 — JWT Best Practices](https://auth0.com/blog/a-look-at-the-latest-draft-for-jwt-bcp/)

---

*Last updated: March 2026 | Spring Boot 3.x · JJWT 0.12.x · Java 17+*
