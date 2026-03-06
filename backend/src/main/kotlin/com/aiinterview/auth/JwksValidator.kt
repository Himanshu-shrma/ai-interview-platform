package com.aiinterview.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.text.ParseException
import java.util.Date

data class JwtClaims(
    val userId: String,
    val email: String,
    val fullName: String?,
)

/**
 * Validates a signed JWT against the cached JWKS and returns the extracted claims.
 * Shared by ClerkJwtAuthFilter (HTTP) and WsAuthHandshakeInterceptor (WebSocket).
 */
@Component
class JwksValidator(private val jwksCache: JwksCache) {

    /**
     * @throws JOSEException  on invalid signature, unsupported key type, or expired token
     * @throws ParseException on malformed JWT string
     */
    suspend fun validate(token: String): JwtClaims {
        val jwkSet = jwksCache.getJwkSet()
        val signed = SignedJWT.parse(token)
        val keyId = signed.header.keyID

        val jwk = if (keyId != null) jwkSet.getKeyByKeyId(keyId) else jwkSet.keys.firstOrNull()
            ?: throw JOSEException("No matching key found in JWKS")

        val verifier = when (jwk) {
            is RSAKey -> RSASSAVerifier(jwk.toRSAPublicKey())
            is ECKey  -> ECDSAVerifier(jwk.toECPublicKey())
            else      -> throw JOSEException("Unsupported JWK type: ${jwk.keyType}")
        }

        if (!signed.verify(verifier)) throw JOSEException("JWT signature invalid")

        val claims = signed.jwtClaimsSet
        val expiry = claims.expirationTime
        if (expiry == null || expiry.before(Date())) throw JOSEException("JWT is expired")

        val subject = claims.subject ?: throw JOSEException("Missing sub claim")
        return JwtClaims(
            userId   = subject,
            email    = claims.getStringClaim("email") ?: "",
            fullName = claims.getStringClaim("name"),
        )
    }
}
