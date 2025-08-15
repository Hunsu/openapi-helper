package fr.asanokaze.openapihelper.model

data class OpenApiOperation(
        val path: String,
        val method: HttpMethod,
        val tags: List<String>,
        val operationId: String,
        val summary: String? = null,
) : OpenApiElement()

enum class HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    TRACE,
    HEAD,
    OPTIONS;

    companion object {
        fun from(name: String): HttpMethod = HttpMethod.valueOf(name.uppercase())
    }
}