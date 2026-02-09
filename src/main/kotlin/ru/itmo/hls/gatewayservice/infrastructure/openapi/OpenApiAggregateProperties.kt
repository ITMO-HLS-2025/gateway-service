package ru.itmo.hls.gatewayservice.infrastructure.openapi

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("openapi.aggregate")
data class OpenApiAggregateProperties(
    var title: String = "HLS Public API",
    var version: String = "v1",
    var excludedPathPrefixes: List<String> = listOf("/inner"),
    var services: List<Service> = emptyList(),
) {
    data class Service(
        var name: String = "",
        var url: String = "",
    )
}

@Configuration
@EnableConfigurationProperties(OpenApiAggregateProperties::class)
class OpenApiAggregateConfig
