package fr.asanokaze.openapihelper.navigation

class SpecResolversFactory {
    companion object {
        fun resolvers(): List<OpenApiSpecResolver> {
            return listOf(
                OpenApiOperationSpecResolver(),
                OpenApiComponentSpecResolver(),
                OpenApiTagSpecResolver()
            )
        }
    }
}