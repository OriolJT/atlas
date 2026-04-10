# OIDC Migration Guide: HS256 to RS256

This guide describes how to migrate Atlas from HS256 symmetric JWT signing to RS256 asymmetric signing, and eventually expose a standards-compliant OIDC discovery endpoint.

---

## Why Migrate

Atlas v1 uses HS256: a single shared secret signs tokens in Identity Service and all other services use the same secret to validate them. This works fine at small scale.

The problem grows with service count. As Atlas adds services — or as other teams integrate with Atlas tokens — every consumer must hold the signing secret. This creates several problems:

1. **Secret rotation requires a coordinated deployment.** All services that validate tokens must deploy simultaneously with the new secret. Miss one, and it rejects all tokens until it redeploys.

2. **Secret sprawl.** Every service that holds the secret is a potential leak point. In a four-service system this is manageable. At twenty services across multiple teams, it's a real risk.

3. **No standard discovery.** Other systems that want to validate Atlas tokens have no standard way to obtain the validation material. They need out-of-band configuration.

RS256 solves all three:
- The private key lives only in Identity Service. No other service ever touches it.
- Other services validate tokens using the public key fetched from a `/.well-known/jwks.json` endpoint.
- Key rotation is a Identity Service concern only. Other services fetch the new public key automatically from the JWKS endpoint.
- Any standard OAuth2/OIDC library can validate Atlas tokens once you expose `/.well-known/openid-configuration`.

---

## Migration Path

The migration is designed to be non-breaking at the token contract level. The JWT claims (`sub`, `tenant_id`, `roles`, `iat`, `exp`) do not change. Only the signing algorithm and key material change.

### Step 1: Generate an RSA Key Pair

Generate a 2048-bit RSA key pair. 4096-bit is also acceptable but has higher CPU cost at token validation time.

```bash
# Generate private key (PKCS#8 format, no passphrase for application use)
openssl genrsa -out atlas-private.pem 2048

# Extract public key
openssl rsa -in atlas-private.pem -pubout -out atlas-public.pem

# Convert private key to PKCS#8 DER (for Java KeyFactory)
openssl pkcs8 -topk8 -nocrypt -in atlas-private.pem -out atlas-private-pkcs8.pem
```

Store the private key in your secret manager. The public key can be stored alongside service configuration — it is not a secret.

### Step 2: Update Identity Service to Sign with the Private Key

Replace the `JwtTokenProvider` signing key construction:

```java
// Before (HS256)
this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

// After (RS256)
byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64);
PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
KeyFactory kf = KeyFactory.getInstance("RSA");
this.privateKey = (RSAPrivateKey) kf.generatePrivate(spec);
```

Update `generateAccessToken` to use the private key:

```java
return Jwts.builder()
        .subject(userId.toString())
        .claim("tenant_id", tenantId.toString())
        .claim("roles", roles)
        .issuedAt(Date.from(now))
        .expiration(Date.from(expiry))
        .signWith(privateKey, Jwts.SIG.RS256)  // was: signWith(secretKey)
        .compact();
```

Add the private key as a new environment variable:

```
ATLAS_JWT_PRIVATE_KEY_BASE64=<base64-encoded PKCS8 private key>
```

### Step 3: Update Consuming Services to Validate with the Public Key

For Workflow, Worker, and Audit services, replace secret-based validation with public key validation:

```java
// Before: validate with shared secret
Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);

// After: validate with public key
byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
KeyFactory kf = KeyFactory.getInstance("RSA");
RSAPublicKey publicKey = (RSAPublicKey) kf.generatePublic(spec);

Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token);
```

Add the public key as a configuration value (not a secret):

```
ATLAS_JWT_PUBLIC_KEY_BASE64=<base64-encoded X.509 public key>
```

The `ATLAS_JWT_SECRET` variable can be removed from all consuming services.

### Step 4: Expose the JWKS Endpoint

Identity Service already has a stub `WellKnownController` that exposes `/.well-known/openid-configuration`. Implement the JWKS endpoint at `/.well-known/jwks.json`:

```java
@GetMapping("/.well-known/jwks.json")
public Map<String, Object> jwks() {
    RSAPublicKey publicKey = jwtTokenProvider.getPublicKey();
    return Map.of(
        "keys", List.of(Map.of(
            "kty", "RSA",
            "use", "sig",
            "alg", "RS256",
            "kid", "atlas-key-1",
            "n", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(publicKey.getModulus().toByteArray()),
            "e", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(publicKey.getPublicExponent().toByteArray())
        ))
    );
}
```

Update `openid-configuration` to set `id_token_signing_alg_values_supported` to `["RS256"]`.

Once the JWKS endpoint is live, consuming services can optionally be updated to fetch the public key from the endpoint on startup rather than from a static environment variable. This enables automatic key rotation.

### Step 5: Key Rotation (Future State)

Once services validate using the JWKS endpoint:

1. Generate a new RSA key pair.
2. Add the new key to the JWKS response alongside the old key (multiple keys in the `keys` array). Use distinct `kid` values.
3. Update Identity Service to sign new tokens with the new private key, setting `kid` in the JWT header.
4. Wait for all existing tokens to expire (15 minutes for access tokens).
5. Remove the old key from the JWKS response.

No coordinated deployment is required. Services fetch the updated JWKS naturally on their next key refresh.

---

## OIDC Discovery Endpoint (Current Stub)

Identity Service exposes a minimal OIDC discovery document at `/.well-known/openid-configuration`. This is currently a static stub:

```
GET http://localhost:8081/.well-known/openid-configuration
```

The stub returns enough metadata to satisfy basic OIDC client discovery: issuer, endpoints, supported algorithms. As Atlas evolves toward full OIDC compliance, this endpoint can be extended with additional claims (scopes_supported, claims_supported, etc.).

The `/.well-known/**` path is explicitly permitted in `SecurityConfig` and requires no authentication.

---

## No-Code-Change Deployment

The OIDC discovery and JWKS stub endpoint requires no changes to business logic. It is:

- A read-only `@RestController` with no dependencies on tenant state or authentication.
- Permitted in `SecurityConfig` under `/.well-known/**`.
- Safe to deploy alongside the existing HS256 configuration.

The stub can be deployed today as groundwork. It becomes functional once the RS256 migration in steps 1-4 is complete.
