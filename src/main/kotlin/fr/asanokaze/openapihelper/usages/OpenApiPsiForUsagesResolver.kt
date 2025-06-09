package fr.asanokaze.openapihelper.usages

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import fr.asanokaze.openapihelper.model.OpenApiOperation

/**
 * Interface for resolving the PSI (Program Structure Interface) element associated with a
 * given OpenAPI operation, allowing IntelliJ IDEA to automatically resolve and display usages.
 *
 * ## Purpose
 * - The primary goal of this interface is to determine and return the corresponding
 *   method or construct (e.g., in the generated client code) for a given OpenAPI operation.
 * - IntelliJ IDEA can then use the returned PSI element to identify and display usages
 *   (e.g., references to the operation in the codebase).
 *
 * ## Key Features
 * - This interface is **not intended** to find the usages of the given operation. Rather,
 *   it provides the PSI element that IntelliJ can use for usage resolution.
 * - Designed to support modular implementations for different code generators (e.g., Kotlin-Spring,
 *   Java clients, TypeScript, etc.), identified by the `generatorName` property.
 *
 * ## Example
 * Given the OpenAPI operation `getUser`, this resolver might return:
 * - A corresponding method in the generated Kotlin client (e.g., `UserApi.getUser`).
 * - A Java method annotated with `@Operation(operationId = "getUser")`.
 *
 * @property generatorName The name of the specific generator the resolver supports (e.g., "spring-cloud", "java", "typescript").
 */
interface OpenApiPsiForUsagesResolver {

    /**
     * The name of the generator this resolver supports (e.g., "spring-cloud", "typescript").
     */
    val generatorName: String

    /**
     * Resolves the PSI element associated with the provided OpenAPI operation. This PSI element
     * will be used by IntelliJ IDEA to display and track usages of the operation in the codebase.
     *
     * @param projectPath The project to which the operation belongs.
     * @param openApiOperation The OpenAPI operation for which the PSI element should be resolved.
     * @return A list of resolved PSI elements (e.g., methods, functions), or an empty list if no matching element is found.
     */
    fun resolve(project: Project, openApiOperation: OpenApiOperation): List<PsiElement>
}