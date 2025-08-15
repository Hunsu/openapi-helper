package fr.asanokaze.openapihelper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import fr.asanokaze.openapihelper.indexing.IndexedOperation
import fr.asanokaze.openapihelper.indexing.OpenApiSpecIndex
import fr.asanokaze.openapihelper.parsing.OpenApiYamlParser
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.psi.KtNamedFunction
import com.intellij.lang.javascript.psi.JSFunction

/**
 * Resolver for finding OpenAPI operation specifications from generated method implementations.
 *
 * This resolver searches for operation definitions in OpenAPI specs by:
 * 1. Extracting the method name as potential operationId
 * 2. Using OpenApiSpecIndex to find matching operations
 * 3. Navigating to the operation definition in the YAML file
 */
class OpenApiOperationSpecResolver : OpenApiSpecResolver {

    private val LOG = Logger.getInstance(OpenApiOperationSpecResolver::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override val resolverName: String = "operation-resolver"

    override fun resolveSpecElement(project: Project, element: PsiElement): List<PsiElement> {
        val operationId = extractOperationId(element) ?: return emptyList()

        LOG.debug("Searching for operation spec for operationId: $operationId")

        val operationKey = "operation:$operationId"
        val operationJsons = FileBasedIndex.getInstance()
            .getValues(OpenApiSpecIndex.KEY, operationKey, GlobalSearchScope.allScope(project))

        if (operationJsons.isEmpty()) {
            LOG.debug("No operation found for operationId: $operationId")
            return emptyList()
        }

        val results = mutableListOf<PsiElement>()

        for (operationJson in operationJsons) {
            try {
                val indexedOperation = json.decodeFromString<IndexedOperation>(operationJson)
                val specElement = findOperationInSpec(project, indexedOperation)
                if (specElement != null) {
                    results.add(specElement)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to deserialize operation: $operationJson", e)
            }
        }

        LOG.debug("Found ${results.size} operation specs for operationId: $operationId")
        return results
    }

    private fun extractOperationId(element: PsiElement): String? {
        return when (element) {
            is PsiMethod -> {
                // For Java methods, extract operationId from @Operation annotation if available
                val operationAnnotation = element.getAnnotation("io.swagger.v3.oas.annotations.Operation")
                val annotationOperationId = operationAnnotation?.findAttributeValue("operationId")?.text?.trim('"')

                // Fall back to method name if no annotation operationId
                annotationOperationId ?: element.name
            }

            is KtNamedFunction -> {
                // Try to extract operationId from annotation (simplified approach)
                // Fall back to function name
                element.name
            }

            is JSFunction -> {
                // For TypeScript functions, use the function name
                // OpenAPI Generator typically generates functions with operationId as name
                element.name?.removeSuffix("Raw")
            }

            else -> null
        }
    }

    private fun findOperationInSpec(project: Project, operation: IndexedOperation): PsiElement? {
        val projectBasePath = project.basePath ?: return null
        val fullPath = "file://$projectBasePath${operation.filePath}"

        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(fullPath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null


        // Find the specific operation in the YAML file
        return OpenApiYamlParser.findOperationElement(psiFile, operation.path, operation.method, operation.operationId)
    }
}