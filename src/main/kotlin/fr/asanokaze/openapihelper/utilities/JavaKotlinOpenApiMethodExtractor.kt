package fr.asanokaze.openapihelper.utilities

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.*
import fr.asanokaze.openapihelper.model.OpenApiOperation

class JavaKotlinOpenApiMethodExtractor {

    companion object {
        private val LOG = Logger.getInstance(JavaKotlinOpenApiMethodExtractor::class.java)

        fun resolveMethod(psiClass: PsiClass, operation: OpenApiOperation): PsiMethod? {
            LOG.debug("Searching for implementation of $operation in ${psiClass.qualifiedName}")
            val method = psiClass.methods
                    .filter { it.name == operation.operationId }
                    .find { method ->
                        val operationAnnotation = method.getAnnotation("io.swagger.v3.oas.annotations.Operation")
                        val requestMappingAnnotation = method.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
                                ?: method.getAnnotation("org.springframework.web.bind.annotation.PutMapping")
                                ?: method.getAnnotation("org.springframework.web.bind.annotation.PostMapping")
                                ?: method.getAnnotation("org.springframework.web.bind.annotation.GetMapping")
                                ?: method.getAnnotation("org.springframework.web.bind.annotation.DeleteMapping")
                                ?: method.getAnnotation("org.springframework.web.bind.annotation.PatchMapping")

                        if (requestMappingAnnotation == null) {
                            LOG.debug("Method ${method.name} doesn't have a RequestMapping annotation")
                            return@find false
                        }

                        val operationId = operationAnnotation?.findAttributeValue("operationId")?.text?.trim('"')
                        if (operationAnnotation != null && operationId != operation.operationId) {
                            LOG.debug("Method ${method.name} has a different operationId than the one in the OpenAPI file")
                            return@find false
                        }

                        val summary = operationAnnotation?.findAttributeValue("summary")?.text?.trim('"')
                        if (operationAnnotation != null && operation.summary != null && summary != operation.summary) {
                            LOG.debug("Method ${method.name} has a different summary than the one in the OpenAPI file")
                            return@find false
                        }

                        val tags = (operationAnnotation?.findAttributeValue("tags") as? PsiArrayInitializerMemberValue)
                                ?.initializers
                                ?.mapNotNull { (it as? PsiLiteralExpression)?.value as? String }
                                ?: emptyList()
                        if (operationAnnotation != null && !tags.containsAll(operation.tags)) {
                            LOG.debug("Method ${method.name} doesn't have all the tags of the one in the OpenAPI file")
                            return@find false
                        }

                        val method = when (val httpMethodAttribute = requestMappingAnnotation.findAttributeValue("method")) {
                            is PsiReferenceExpression -> httpMethodAttribute.referenceName?.replace("RequestMethod.", "")
                            is PsiArrayInitializerMemberValue -> httpMethodAttribute.initializers
                                    .firstOrNull()
                                    ?.text
                                    ?.replace("org.springframework.web.bind.annotation.", "")
                                    ?.replace("RequestMethod.", "")

                            else -> null
                        }
                        val path = when (val httpPathAttribute = requestMappingAnnotation.findAttributeValue("value")) {
                            is PsiReferenceExpression -> httpPathAttribute.referenceName
                            is PsiArrayInitializerMemberValue -> httpPathAttribute.initializers.firstOrNull()?.text?.unquote()
                            else -> null
                        }

                        LOG.debug("Found method: $method, path: $path")
                        path != null && method != null && method.equals(operation.method.name, ignoreCase = true) &&
                                (path == operation.path || !operation.path.startsWith("/"))
                    } ?: return null

            return if (method.name.startsWith('_')) {
                val methodNameWithoutUnderscore = method.name.substring(1)
                psiClass.findMethodsByName(methodNameWithoutUnderscore, false).firstOrNull() ?: method
            } else method
        }

        private fun String?.unquote(): String? {
            if (this == null) return null

            return when {
                (this.startsWith('"') && this.endsWith('"')) ||
                        (this.startsWith('\'') && this.endsWith('\'')) -> {
                    this.substring(1, this.length - 1)
                }

                else -> this
            }
        }
    }

}