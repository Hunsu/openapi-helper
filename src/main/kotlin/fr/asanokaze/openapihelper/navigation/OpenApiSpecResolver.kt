package fr.asanokaze.openapihelper.navigation

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

/**
 * Interface for resolving OpenAPI specification elements from generated code.
 * 
 * This interface supports navigation from generated Java/Kotlin code back to the
 * corresponding OpenAPI specification elements.
 */
interface OpenApiSpecResolver {

    /**
     * The name of the resolver (e.g., "operation-resolver", "component-resolver").
     */
    val resolverName: String

    /**
     * Resolves the OpenAPI specification element(s) associated with the provided PSI element.
     *
     * @param project The project context
     * @param element The PSI element from generated code (class, method, etc.)
     * @return List of PSI elements in OpenAPI spec files, or empty list if no matches found
     */
    fun resolveSpecElement(project: Project, element: PsiElement): List<PsiElement>
}