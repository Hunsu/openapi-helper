package fr.asanokaze.openapihelper.model

data class OpenApiComponent(
        val name: String,
        val type: String?,
) : OpenApiElement()