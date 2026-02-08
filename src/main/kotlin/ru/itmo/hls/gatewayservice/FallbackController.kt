package ru.itmo.hls.gatewayservice

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
class FallbackController {

    @GetMapping("/fallback/order-service")
    fun orderServiceFallback(): Mono<ResponseEntity<Map<String, String>>> {
        return Mono.just(
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("message" to "order-service is temporarily unavailable"))
        )
    }

    @GetMapping("/fallback/show-manager")
    fun showManagerFallback(): Mono<ResponseEntity<Map<String, String>>> {
        return Mono.just(
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("message" to "show-manager is temporarily unavailable"))
        )
    }

    @GetMapping("/fallback/theatre-manager")
    fun theatreManagerFallback(): Mono<ResponseEntity<Map<String, String>>> {
        return Mono.just(
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("message" to "theatre-manager is temporarily unavailable"))
        )
    }
}
