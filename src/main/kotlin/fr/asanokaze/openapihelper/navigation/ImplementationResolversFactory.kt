package fr.asanokaze.openapihelper.navigation

class ImplementationResolversFactory {
    companion object {
        fun resolvers(): List<OpenApiImplementationOperationResolver> {
            return listOf(
                    KotlinSpringOpenApiOperationImplementationResolver()
            )
        }
    }
}