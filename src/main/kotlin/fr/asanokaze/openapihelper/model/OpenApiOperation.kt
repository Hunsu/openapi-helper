package fr.asanokaze.openapihelper.model

data class OpenApiOperation(
        val path: String,
        val method: String,
        val tags: List<String>,
        val operationId: String,
        val summary: String? = null,
)