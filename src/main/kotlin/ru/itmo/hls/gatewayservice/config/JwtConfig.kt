package ru.itmo.hls.gatewayservice.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(JwtProperties::class)
class JwtConfig

@ConfigurationProperties(prefix = "auth.jwt")
data class JwtProperties(
    var secret: String = ""
)
