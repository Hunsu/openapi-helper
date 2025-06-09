package fr.asanokaze.openapihelper.usages

class OpenApiPsiForUsagesResolversFactory {

    companion object {
        fun resolvers(): List<OpenApiPsiForUsagesResolver> = listOf(
                SpringCloudPsiForUsagesResolver(),
                TypeScriptPsiForUsagesResolver()
        )
    }
}