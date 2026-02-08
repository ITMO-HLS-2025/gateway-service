package ru.itmo.hls.gatewayservice.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@RestController
class OpenApiAggregateController(
    private val webClientBuilder: WebClient.Builder,
    private val objectMapper: ObjectMapper,
    private val properties: OpenApiAggregateProperties,
) {
    @GetMapping("/v3/api-docs/public", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun publicDocs(request: ServerHttpRequest): Mono<ResponseEntity<JsonNode>> {
        if (properties.services.isEmpty()) {
            val emptyBody = objectMapper.createObjectNode()
                .put("openapi", "3.1.0")
                .set<ObjectNode>("info", objectMapper.createObjectNode()
                    .put("title", properties.title)
                    .put("version", properties.version)
                )
            return Mono.just(ResponseEntity.ok(emptyBody))
        }

        val baseUrl = buildBaseUrl(request)
        val client = webClientBuilder.build()
        val fetches = properties.services.map { service ->
            val targetUrl = resolveServiceUrl(baseUrl, service.url)
            client.get()
                .uri(targetUrl)
                .retrieve()
                .bodyToMono(ObjectNode::class.java)
        }

        return Mono.zip(fetches) { results ->
            val docs = results.map { it as ObjectNode }
            val merged = mergeDocs(docs, baseUrl)
            ResponseEntity.ok(merged)
        }
    }

    private fun buildBaseUrl(request: ServerHttpRequest): String {
        val uri = request.uri
        val scheme = uri.scheme ?: "http"
        val authority = uri.authority ?: "localhost"
        return "$scheme://$authority"
    }

    private fun resolveServiceUrl(baseUrl: String, serviceUrl: String): String {
        return if (serviceUrl.startsWith("http://") || serviceUrl.startsWith("https://")) {
            serviceUrl
        } else {
            val normalizedBase = baseUrl.removeSuffix("/")
            val normalizedPath = if (serviceUrl.startsWith("/")) serviceUrl else "/$serviceUrl"
            "$normalizedBase$normalizedPath"
        }
    }

    private fun mergeDocs(docs: List<ObjectNode>, baseUrl: String): ObjectNode {
        val result = objectMapper.createObjectNode()
        val openapiVersion = docs.firstOrNull()?.path("openapi")?.asText("3.1.0") ?: "3.1.0"
        result.put("openapi", openapiVersion)

        result.set<ObjectNode>(
            "info",
            objectMapper.createObjectNode()
                .put("title", properties.title)
                .put("version", properties.version)
        )
        result.set<ArrayNode>(
            "servers",
            objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("url", baseUrl))
        )

        val mergedPaths = objectMapper.createObjectNode()
        val mergedComponents = objectMapper.createObjectNode()
        val mergedTags = linkedMapOf<String, JsonNode>()
        val mergedSecurity = objectMapper.createArrayNode()

        docs.forEach { doc ->
            val paths = doc.path("paths")
            if (paths is ObjectNode) {
                paths.fields().forEachRemaining { (path, value) ->
                    if (shouldIncludePath(path)) {
                        mergedPaths.set<JsonNode>(path, value)
                    }
                }
            }

            val components = doc.path("components")
            if (components is ObjectNode) {
                mergeObjectFields(mergedComponents, components)
            }

            val tags = doc.path("tags")
            if (tags is ArrayNode) {
                tags.forEach { tag ->
                    val name = tag.path("name").asText(null)
                    if (name != null && !mergedTags.containsKey(name)) {
                        mergedTags[name] = tag
                    }
                }
            }

            val security = doc.path("security")
            if (security is ArrayNode) {
                security.forEach { requirement ->
                    if (!containsSecurityRequirement(mergedSecurity, requirement)) {
                        mergedSecurity.add(requirement)
                    }
                }
            }
        }

        result.set<ObjectNode>("paths", mergedPaths)

        if (mergedComponents.size() > 0) {
            result.set<ObjectNode>("components", mergedComponents)
        }

        stripAuthorizationParams(mergedPaths)

        if (mergedSecurity.size() > 0) {
            result.set<ArrayNode>("security", mergedSecurity)
        }

        if (mergedTags.isNotEmpty()) {
            val tagsArray = objectMapper.createArrayNode()
            mergedTags.values.forEach { tagsArray.add(it) }
            result.set<ArrayNode>("tags", tagsArray)
        }

        return result
    }

    private fun mergeObjectFields(target: ObjectNode, source: ObjectNode) {
        source.fields().forEachRemaining { (fieldName, value) ->
            val existing = target.get(fieldName)
            if (existing == null) {
                target.set<JsonNode>(fieldName, value)
                return@forEachRemaining
            }

            if (existing is ObjectNode && value is ObjectNode) {
                value.fields().forEachRemaining { (childName, childValue) ->
                    if (!existing.has(childName)) {
                        existing.set<JsonNode>(childName, childValue)
                    }
                }
            }
        }
    }

    private fun shouldIncludePath(path: String): Boolean {
        if (!path.startsWith("/")) {
            return true
        }
        return properties.excludedPathPrefixes.none { prefix ->
            path.startsWith(prefix)
        }
    }

    private fun stripAuthorizationParams(paths: ObjectNode) {
        val httpMethods = setOf(
            "get", "post", "put", "delete", "patch", "head", "options", "trace"
        )

        paths.fields().forEachRemaining { (_, pathItem) ->
            if (pathItem !is ObjectNode) return@forEachRemaining

            removeAuthorizationParam(pathItem)

            pathItem.fields().forEachRemaining { (method, operationNode) ->
                if (!httpMethods.contains(method) || operationNode !is ObjectNode) {
                    return@forEachRemaining
                }
                removeAuthorizationParam(operationNode)
            }
        }
    }

    private fun removeAuthorizationParam(node: ObjectNode) {
        val params = node.get("parameters")
        if (params !is ArrayNode) {
            return
        }
        val iterator = params.iterator()
        while (iterator.hasNext()) {
            val param = iterator.next()
            val name = param.path("name").asText("")
            val location = param.path("in").asText("")
            if (name.equals("Authorization", ignoreCase = true) && location == "header") {
                iterator.remove()
            }
        }
    }

    private fun containsSecurityRequirement(target: ArrayNode, candidate: JsonNode): Boolean {
        return target.any { existing -> existing == candidate }
    }

}
