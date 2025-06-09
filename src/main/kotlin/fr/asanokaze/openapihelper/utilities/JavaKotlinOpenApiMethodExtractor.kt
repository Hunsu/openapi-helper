package fr.asanokaze.openapihelper.utilities

import com.intellij.psi.*
import fr.asanokaze.openapihelper.model.OpenApiOperation

class JavaKotlinOpenApiMethodExtractor {

    companion object {

        fun resolveMethod(psiClass: PsiClass, operation: OpenApiOperation): PsiMethod? {
            val method = psiClass.methods.find { method ->
                val operationAnnotation = method.getAnnotation("io.swagger.v3.oas.annotations.Operation")
                val requestMappingAnnotation = method.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
                        ?: method.getAnnotation("org.springframework.web.bind.annotation.PutMapping")
                        ?: method.getAnnotation("org.springframework.web.bind.annotation.PostMapping")
                        ?: method.getAnnotation("org.springframework.web.bind.annotation.GetMapping")
                        ?: method.getAnnotation("org.springframework.web.bind.annotation.DeleteMapping")
                        ?: method.getAnnotation("org.springframework.web.bind.annotation.PatchMapping")

                if (operationAnnotation == null || requestMappingAnnotation == null) return@find false

                val operationId = operationAnnotation.findAttributeValue("operationId")?.text?.trim('"')
                if (operationId != operation.operationId) return@find false

                val summary = operationAnnotation.findAttributeValue("summary")?.text?.trim('"')
                if (operation.summary != null && summary != operation.summary) return@find false

                val tags = (operationAnnotation.findAttributeValue("tags") as? PsiArrayInitializerMemberValue)
                        ?.initializers
                        ?.mapNotNull { (it as? PsiLiteralExpression)?.value as? String }
                        ?: emptyList()
                if (!tags.containsAll(operation.tags)) return@find false

                val httpMethodAttribute = requestMappingAnnotation.findAttributeValue("method")
                val method = when (httpMethodAttribute) {
                    is PsiReferenceExpression -> httpMethodAttribute.referenceName?.replace("RequestMethod.", "")
                    is PsiArrayInitializerMemberValue -> httpMethodAttribute.initializers.firstOrNull()?.text?.replace("org.springframework.web.bind.annotation.RequestMethod.", "")
                    else -> null
                }
                val httpPathAttribute = requestMappingAnnotation.findAttributeValue("value")
                val path = when (httpPathAttribute) {
                    is PsiReferenceExpression -> httpPathAttribute.referenceName
                    is PsiArrayInitializerMemberValue -> httpPathAttribute.initializers.firstOrNull()?.text?.unquote()
                    else -> null
                }

                path != null && method != null && method.equals(operation.method, ignoreCase = true) &&
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