package fr.asanokaze.openapihelper

import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLPsiElement
import org.jetbrains.yaml.psi.YAMLScalar
import java.util.*

private val LOG = Logger.getInstance(OpenApiOperationIdUsageSearcher::class.java)

class OpenApiOperationIdUsageSearcher : PsiReferenceContributor() {


    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLKeyValue::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val yamlKeyValue = element as? YAMLKeyValue ?: return PsiReference.EMPTY_ARRAY
                val keyText = yamlKeyValue.keyText

                // Ensure the key is "operationId"
                if (keyText != "operationId") return PsiReference.EMPTY_ARRAY

                // Extract the operationId value (e.g., updateUser)
                val operationIdValue = (yamlKeyValue.value as? YAMLScalar)?.textValue ?: return PsiReference.EMPTY_ARRAY

                LOG.info("Will search for usages of operationId: $operationIdValue")

                // Pass the full range of the operationId value to the reference
                val rangeInElement = yamlKeyValue.value?.textRange?.shiftLeft(yamlKeyValue.textRange.startOffset)
                        ?: TextRange(0, keyText.length)

                return arrayOf(OperationIdReference(yamlKeyValue, rangeInElement))
            }
        })
    }
}

class OperationIdReference(element: YAMLPsiElement, range: TextRange) : PsiReferenceBase<PsiElement>(element, range), PsiPolyVariantReference {
    private val cachedResults = WeakHashMap<PsiElement, PsiElement?>()

    override fun resolve(): PsiElement? {
        if (DumbService.isDumb(element.project)) return null // Avoid running during indexing

        ReadAction.nonBlocking<PsiElement?> {
            val operation = OpenApiParser().extractOperation(element) ?: return@nonBlocking null

            val fileUrls = FileBasedIndex.getInstance()
                    .getValues(OpenApiMethodIndex.KEY, operation.operationId, GlobalSearchScope.projectScope(element.project))

            LOG.info("Found fileUrls: ${fileUrls.joinToString(",")}")
            val files = fileUrls.asSequence() // Use sequence for lazy evaluation
                    .mapNotNull { fileUrl -> VirtualFileManager.getInstance().findFileByUrl(fileUrl) }
                    .mapNotNull { virtualFile -> PsiManager.getInstance(this@OperationIdReference.element.project).findFile(virtualFile) }
            val classes: List<PsiClass> = getClasses(files)
            LOG.info("Found classes: ${classes.joinToString(",") { it.qualifiedName ?: "" }}")
            val psiMethod = process(operation, classes)
            if (psiMethod != null) {
                cachedResults[element] = psiMethod
            } else {
                LOG.info("Didn't find usages in Java/Kotlin code, let's check JavaScript code")
                val jsFiles = files.filter { it is JSFile }.toList()
                LOG.info("Let's check JS files: ${jsFiles.joinToString(",") { it.name }}")
                val jsFunction = jsFiles.firstNotNullOfOrNull {
                    val classes = PsiTreeUtil.getChildrenOfTypeAsList(it, JSClass::class.java)
                    findFunctionBasedOnOperation(classes, operation)
                }
                if (jsFunction != null) {
                    LOG.info("Found usages in JavaScript code: ${jsFunction.containingFile.name}:${jsFunction.name}")
                    cachedResults[element] = jsFunction
                }
            }
            cachedResults[element]
        }.inSmartMode(element.project)
                .submit(AppExecutorUtil.getAppExecutorService())

        return cachedResults[element]
    }

    private fun findFunctionBasedOnOperation(
            classes: List<JSClass>,
            operation: OpenApiOperation
    ): JSFunction? {
        val pathRegex = Regex("""path\s*:\s*["'`](.*?)["'`]""")
        val methodRegex = Regex("""method\s*:\s*["'`](.*?)["'`]""")
        LOG.info("Lets check ${classes.map { it.name }} for $operation")
        for (jsClass in classes) {
            val functions = jsClass.functions
            val rawFunction = functions.find { it.name == "${operation.operationId}Raw" }

            if (rawFunction != null) {
                LOG.info("Found \"${operation.operationId}Raw\" function for ${jsClass.name}")
                val functionBodyText = rawFunction.text
                val pathMatch = pathRegex.find(functionBodyText)?.groups?.get(1)?.value
                val methodMatch = methodRegex.find(functionBodyText)?.groups?.get(1)?.value
                if ((pathMatch == operation.path || !operation.path.startsWith("/")) &&
                        methodMatch.equals(operation.method, ignoreCase = true)) {
                    val nonRawFunction = functions.find { it.name == operation.operationId }
                    if (nonRawFunction != null) {
                        LOG.info("${operation.operationId}Raw function has been replaced by ${nonRawFunction.name}")
                        return nonRawFunction
                    }

                    return rawFunction
                } else {
                    LOG.info("${rawFunction.name} doesn't match the operationId and path/method")
                }
            } else {
                LOG.info("Didn't find \"${operation.operationId}Raw\" function for ${jsClass.name}")
            }
        }
        return null
    }

    private fun getClasses(files: Sequence<PsiFile>): List<PsiClass> {
        val files = files
                .filter { it is KtFile || it is PsiJavaFile }
                .toList()
        val classes: List<PsiClass> = if (files.size == 1) {
            val psiFile = files
                    .firstNotNullOfOrNull {
                        when (it) {
                            is KtFile -> {
                                it
                            }

                            is PsiJavaFile -> {
                                it
                            }

                            else -> {
                                null
                            }
                        }
                    }

            LOG.info("Found psiFile: $psiFile")
            if (psiFile != null) {
                val psiShortNamesCache = PsiShortNamesCache.getInstance(psiFile.project)
                val name = psiFile.name.removeSuffix(".kt").removeSuffix(".java")
                val classes = psiShortNamesCache.getClassesByName(name, GlobalSearchScope.projectScope(psiFile.project)).toList()
                LOG.info("Found classes: ${classes.joinToString(",") { it.qualifiedName ?: "" }}")
                classes
            } else {
                emptyList()
            }
        } else {
            files.filter { it is KtFile || it is PsiJavaFile }
                    .map {
                        when (it) {
                            is KtFile -> {
                                it.classes.toList()
                            }

                            is PsiJavaFile -> {
                                it.classes.toList()
                            }

                            else -> {
                                emptyList()
                            }
                        }
                    }
                    .flatten()
                    .toList()
        }
        return classes
    }

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult?> {
        return ResolveResult.EMPTY_ARRAY
    }
}

private fun process(openApiOperation: OpenApiOperation, classes: List<PsiClass>): PsiMethod? {
    val clazz = classes.firstOrNull { klass ->
        !klass.hasAnnotation("org.springframework.web.bind.annotation.RestController")
        !klass.methods.any { it.name == "getDelegate" }
    }
    LOG.info("Found : ${clazz?.qualifiedName}")
    return clazz?.let { findMethodWithOperation(it, openApiOperation) }
}

private fun findMethodWithOperation(psiClass: PsiClass, openApiOperation: OpenApiOperation): PsiMethod? {
    return ReadAction.compute<PsiMethod?, RuntimeException> {
        psiClass.methods.find { method ->
            checkMethodMatches(method, openApiOperation)
        }
    }
}

// Extract method matching logic to separate function for better readability
private fun checkMethodMatches(method: PsiMethod, operation: OpenApiOperation): Boolean {
    val operationAnnotation = method.getAnnotation("io.swagger.v3.oas.annotations.Operation")
    val requestMappingAnnotation = findRequestMappingAnnotation(method) ?: return false

    // Compare operationId
    if (operationAnnotation != null && !matchesOperationId(operationAnnotation, operation)) return false

    // Compare summary
    if (operationAnnotation != null && !matchesSummary(operationAnnotation, operation)) return false

    // Compare tags
    if (operationAnnotation != null && !matchesTags(operationAnnotation, operation)) return false

    // Compare HTTP method and path
    return matchesHttpMethodAndPath(requestMappingAnnotation, operation)
}

// Add helper functions for better organization and readability
private fun findRequestMappingAnnotation(method: PsiMethod): PsiAnnotation? {
    return method.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
            ?: method.getAnnotation("org.springframework.web.bind.annotation.PutMapping")
            ?: method.getAnnotation("org.springframework.web.bind.annotation.PostMapping")
            ?: method.getAnnotation("org.springframework.web.bind.annotation.GetMapping")
            ?: method.getAnnotation("org.springframework.web.bind.annotation.DeleteMapping")
            ?: method.getAnnotation("org.springframework.web.bind.annotation.PatchMapping")
}

// Add other helper functions for matching various aspects...
private fun matchesOperationId(operationAnnotation: PsiAnnotation, operation: OpenApiOperation): Boolean {
    val operationId = operationAnnotation.findAttributeValue("operationId")?.text?.trim('"')
    return operationId == null || operationId == operation.operationId
}

private fun matchesSummary(operationAnnotation: PsiAnnotation, operation: OpenApiOperation): Boolean {
    val summary = operationAnnotation.findAttributeValue("summary")?.text?.trim('"')
    return summary == null || operation.summary == null || summary == operation.summary
}

private fun matchesTags(operationAnnotation: PsiAnnotation, operation: OpenApiOperation): Boolean {
    val tags = (operationAnnotation.findAttributeValue("tags") as? PsiArrayInitializerMemberValue)?.initializers?.mapNotNull { (it as? PsiLiteralExpression)?.value as? String }
    return tags == null || tags.containsAll(operation.tags)
}

private fun matchesHttpMethodAndPath(requestMappingAnnotation: PsiAnnotation, operation: OpenApiOperation): Boolean {
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

    return path != null && method != null && method.equals(operation.method, ignoreCase = true) &&
            (path == operation.path || !operation.path.startsWith("/"))
}

private fun String?.unquote(): String? {
    if (this == null) return null

    return when {
        (this.startsWith('"') && this.endsWith('"')) || (this.startsWith('\'') && this.endsWith('\'')) -> {
            this.substring(1, this.length - 1)
        }

        else -> this
    }
}