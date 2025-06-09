package fr.asanokaze.openapihelper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.util.indexing.FileBasedIndex
import fr.asanokaze.openapihelper.indexing.KEY
import fr.asanokaze.openapihelper.model.OpenApiOperation
import fr.asanokaze.openapihelper.utilities.JavaKotlinOpenApiMethodExtractor
import org.jetbrains.kotlin.psi.KtFile

/**
 * Resolves the implementation of an OpenAPI operation in a project that uses the Kotlin-Spring generator.
 *
 * This class searches for Kotlin implementations of OpenAPI operations by:
 * - Leveraging a custom IntelliJ File-Based Index (`OpenApiMethodIndex`) to identify the files containing the generated code for the specified operation ID.
 * - Navigating from the generated methods to the actual implementation, supporting the delegate pattern utilized by the Kotlin-Spring generator.
 * ### Main Features:
 * 1. **Index-Based Resolution**: Uses `FileBasedIndex` to quickly identify files containing the operation.
 * 2. **Kotlin-Spring Delegate Pattern Resolution**: If the controller contains the `getDelegate` method,
 *    the delegate class is identified and searched for the method implementation.
 * 3. **Spring Controller Identification**: Ensures that methods are searched within classes annotated with
 *    `@RestController` or `@RequestMapping`, ensuring compatibility with the Spring framework.
 *
 * ### Supported Annotations:
 * - `@io.swagger.v3.oas.annotations.Operation`
 * - Spring HTTP mapping annotations (`@GetMapping`, `@PostMapping`, etc.)
 *
 * ### Example:
 * Given an OpenAPI operation ID (e.g., "getUser"), this resolver will navigate:
 * - From the OpenAPI definition → to the generated code → to the actual implementation in the Spring application.
 */
class KotlinSpringOpenApiOperationImplementationResolver : OpenApiImplementationOperationResolver {

    val LOG = Logger.getInstance(KotlinSpringOpenApiOperationImplementationResolver::class.java)

    override val generatorName: String
        get() = "kotlin-spring"

    override fun resolveImplementation(project: Project, operation: OpenApiOperation): List<PsiElement> {
        val operationId = operation.operationId
        val fileUrls = FileBasedIndex.getInstance()
                .getValues(KEY, operationId, GlobalSearchScope.projectScope(project))

        val projectBasePath = project.basePath ?: ""
        return fileUrls.asSequence()
                .map { relativePath -> "file://$projectBasePath$relativePath" }
                .mapNotNull { fileUrl -> VirtualFileManager.getInstance().findFileByUrl(fileUrl) }
                .mapNotNull { virtualFile -> PsiManager.getInstance(project).findFile(virtualFile) }
                .mapNotNull { psiFile ->
                    when (psiFile) {
                        is KtFile -> processKotlinFile(psiFile, operation)
                        else -> null
                    }
                }.toList()
    }

    /**
     * Processes a Kotlin file to find the implementation of a given OpenAPI operation.
     * @param psiFile The Kotlin file to be processed.
     * @param operationRef The OpenAPI operation whose implementation is being searched for.
     * @return The PSI element representing the implementation, or null if none is found.
     */
    private fun processKotlinFile(psiFile: KtFile, operationRef: OpenApiOperation): PsiElement? {
        val restControllerClass = psiFile.classes.firstOrNull {
            isSpringController(it) || hasGetDelegateMethod(it)
        }
        return restControllerClass?.let { findMethodWithOperation(it, operationRef) }
    }

    /**
     * Checks if a class is a Spring controller by verifying its annotations.
     * @param klass The class to check.
     * @return True if the class is annotated with `@RestController` or `@RequestMapping`.
     */
    private fun isSpringController(klass: PsiClass): Boolean =
            klass.hasAnnotation("org.springframework.web.bind.annotation.RestController") ||
                    klass.hasAnnotation("rg.springframework.web.bind.annotation.RequestMapping")

    /**
     * Finds the method corresponding to the provided OpenAPI operation in the given class.
     *
     * This function also supports the delegate pattern used in Kotlin-Spring, where the actual implementation may
     * reside in a delegate class.
     *
     * @param psiClass The class being searched.
     * @param operation The OpenAPI operation details.
     * @return The corresponding `PsiMethod` if found, otherwise null.
     */
    private fun findMethodWithOperation(psiClass: PsiClass, operation: OpenApiOperation): PsiMethod? {
        var method: PsiMethod?
        val module = ModuleUtilCore.findModuleForPsiElement(psiClass)
        val scope = (module?.moduleWithDependentsScope
                ?: GlobalSearchScope.projectScope(psiClass.project))

        LOG.debug("Searching for implementation of $operation in ${psiClass.qualifiedName} using scope $scope")
        method = JavaKotlinOpenApiMethodExtractor.resolveMethod(psiClass, operation) ?: return null
        if (hasGetDelegateMethod(psiClass)) {
            val delegateClass = JavaPsiFacade.getInstance(psiClass.project)
                    .findClass("${psiClass.qualifiedName}Delegate", scope)

            if (delegateClass != null) {
                method = delegateClass.findMethodBySignature(method, false) ?: method
            }
        }
        LOG.debug("Found implementation of $operation: $method")
        LOG.debug("Searching for overriding methods of $method using scope $scope")
        val overridingMethods = OverridingMethodsSearch.search(method, scope, false).findAll()
        LOG.debug("Found overriding methods: ${overridingMethods.joinToString(", ")}")
        if (overridingMethods.isNotEmpty()) {
            return overridingMethods.first()
        }

        return method
    }

    /**
     * Checks if a class has a `getDelegate` method, which signifies usage of Kotlin-Spring's delegate pattern.
     * @param klass The class to check.
     * @return True if the `getDelegate` method exists.
     */
    private fun hasGetDelegateMethod(klass: PsiClass): Boolean = klass.methods.any { it.name == "getDelegate" }
}