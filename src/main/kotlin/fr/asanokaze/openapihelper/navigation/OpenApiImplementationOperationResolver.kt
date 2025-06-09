package fr.asanokaze.openapihelper.navigation

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import fr.asanokaze.openapihelper.model.OpenApiOperation

/**
 * Interface for resolving the implementation of a given OpenAPI operation.
 *
 * This interface supports multiple implementations depending on the specific code generator
 * used (e.g., Spring, Python, Go, etc.). Each implementation should handle the resolution
 * logic for the corresponding generator.
 */
interface OpenApiImplementationOperationResolver {

    /**
     * The name of the generator this resolver supports (e.g., "kotlin-spring", "Python", "Go").
     */
    val generatorName: String

    /**
     * Resolves the implementation element (e.g., a method, function, or other construct) associated
     * with the provided OpenAPI operation.
     *
     * @param operation The OpenAPI operation to resolve.
     * @exception project The project
     * @return The implementation element (e.g., method, function), or `null` if no matching element is found.
     */
    fun resolveImplementation(project: Project, operation: OpenApiOperation): List<PsiElement>
}