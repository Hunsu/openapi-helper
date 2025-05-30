package fr.asanokaze.openapihelper

data class OpenApiOperation(
        val path: String,
        val method: String,
        val tags: List<String>,
        val operationId: String,
        val summary: String? = null,
)