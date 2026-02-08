package ru.itmo.hls.gatewayservice.filter

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import ru.itmo.hls.gatewayservice.config.JwtProperties
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory

@Component
class RoleAccessGatewayFilterFactory(
    private val jwtProperties: JwtProperties
) : AbstractGatewayFilterFactory<RoleAccessGatewayFilterFactory.Config>(Config::class.java) {

    data class Config(
        var roles: List<String> = emptyList(),
        var methods: List<String> = emptyList()
    )

    private data class TokenClaims(
        val role: String,
        val theatreId: Long?
    )

    override fun shortcutFieldOrder(): List<String> = listOf("roles", "methods")

    override fun apply(config: Config): GatewayFilter {
        val allowedRoles = config.roles.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val allowedMethods = config.methods.map { HttpMethod.valueOf(it.trim().uppercase()) }.toSet()

        return GatewayFilter { exchange, chain ->
            val method = exchange.request.method
            if (method == HttpMethod.OPTIONS) return@GatewayFilter chain.filter(exchange)

            if (allowedMethods.isNotEmpty() && method !in allowedMethods) {
                return@GatewayFilter chain.filter(exchange)
            }

            val authHeader = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
                ?: return@GatewayFilter unauthorized(exchange, "Missing Authorization header")
            val token = extractBearerToken(authHeader)
                ?: return@GatewayFilter unauthorized(exchange, "Invalid Authorization header")
            val claims = parseClaims(token) ?: return@GatewayFilter unauthorized(exchange, "Invalid token")
            val role = claims.role

            if (allowedRoles.isNotEmpty() && role !in allowedRoles) {
                return@GatewayFilter unauthorized(exchange, "Role $role not allowed")
            }

            if (role == "THEATRE_DIRECTOR") {
                val theatreId = claims.theatreId
                    ?: return@GatewayFilter unauthorized(exchange, "Missing theatreId for director")
                val targetTheatreId = extractTheatreId(exchange)
                if (targetTheatreId != null && targetTheatreId != theatreId) {
                    return@GatewayFilter unauthorized(exchange, "Theatre mismatch")
                }
            }

            return@GatewayFilter chain.filter(exchange)
        }
    }

    private fun extractBearerToken(header: String): String? {
        val prefix = "bearer "
        val normalized = header.trim()
        if (!normalized.lowercase().startsWith(prefix)) {
            return null
        }
        return normalized.substring(prefix.length).trim()
    }

    private fun parseClaims(token: String): TokenClaims? {
        val secretBytes = jwtProperties.secret.toByteArray(StandardCharsets.UTF_8)
        val key = Keys.hmacShaKeyFor(secretBytes)
        return try {
            val claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
            val role = claims["role"]?.toString() ?: return null
            val theatreId = when (val rawId = claims["theatreId"]) {
                is Number -> rawId.toLong()
                is String -> rawId.toLongOrNull()
                else -> null
            }
            TokenClaims(role = role, theatreId = theatreId)
        } catch (ex: Exception) {
            null
        }
    }

    private fun extractTheatreId(exchange: ServerWebExchange): Long? {
        val path = exchange.request.uri.path

        val theatrePathMatch = THEATRE_PATH_REGEX.find(path)
        if (theatrePathMatch != null) {
            return theatrePathMatch.groupValues[1].toLongOrNull()
        }

        val showTheatreMatch = SHOWS_THEATRE_PATH_REGEX.find(path)
        if (showTheatreMatch != null) {
            return showTheatreMatch.groupValues[1].toLongOrNull()
        }

        val queryTheatreId = exchange.request.queryParams.getFirst("theatreId")
        return queryTheatreId?.toLongOrNull()
    }

    private fun unauthorized(exchange: ServerWebExchange, reason: String): reactor.core.publisher.Mono<Void> {
        log.warn(
            "Unauthorized request: method={} path={} reason={}",
            exchange.request.method,
            exchange.request.uri.path,
            reason
        )
        exchange.response.statusCode = HttpStatus.UNAUTHORIZED
        return exchange.response.setComplete()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RoleAccessGatewayFilterFactory::class.java)
        private val THEATRE_PATH_REGEX = Regex("^/api/theatres/(\\d+)(/.*)?$")
        private val SHOWS_THEATRE_PATH_REGEX = Regex("^/api/shows/theatre/(\\d+)(/.*)?$")
    }
}
