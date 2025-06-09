package fr.asanokaze.openapihelper.usages

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.util.indexing.FileBasedIndex
import fr.asanokaze.openapihelper.indexing.KEY
import fr.asanokaze.openapihelper.model.OpenApiOperation
import fr.asanokaze.openapihelper.utilities.JavaKotlinOpenApiMethodExtractor
import org.jetbrains.kotlin.psi.KtFile

/**
 * Resolver for finding the associated method in the generated client when using the Spring Cloud generator.
 * This class provides the implementation for resolving PSI elements associated with a given OpenAPI operation.
 */
class SpringCloudPsiForUsagesResolver : OpenApiPsiForUsagesResolver {

    private val LOG = Logger.getInstance(SpringCloudPsiForUsagesResolver::class.java)

    override val generatorName: String
        get() = "spring-cloud"

    override fun resolve(project: Project, openApiOperation: OpenApiOperation): List<PsiElement> {
        val fileUrls = FileBasedIndex.getInstance()
                .getValues(KEY, openApiOperation.operationId, GlobalSearchScope.projectScope(project))

        LOG.info("Found fileUrls: ${fileUrls.joinToString(",")}")
        val files = fileUrls.asSequence() // Use sequence for lazy evaluation
                .mapNotNull { fileUrl -> VirtualFileManager.getInstance().findFileByUrl(fileUrl) }
                .mapNotNull { virtualFile -> PsiManager.getInstance(project).findFile(virtualFile) }
        val classes: List<PsiClass> = getClasses(files)
        LOG.info("Found classes: ${classes.joinToString(",") { it.qualifiedName ?: "" }}")
        val psiMethod = resolveMethod(openApiOperation, classes)
        if (psiMethod != null) {
            listOf(psiMethod)
        }
        return emptyList()
    }

    private fun resolveMethod(openApiOperation: OpenApiOperation, classes: List<PsiClass>): PsiMethod? {
        val clazz = classes.firstOrNull { klass ->
            !klass.hasAnnotation("org.springframework.web.bind.annotation.RestController")
            !klass.methods.any { it.name == "getDelegate" }
        }
        LOG.info("Found : ${clazz?.qualifiedName}")
        return clazz?.let { JavaKotlinOpenApiMethodExtractor.resolveMethod(it, openApiOperation) }
    }

    private fun getClasses(files: Sequence<PsiFile>): List<PsiClass> {
        val files = files
                .filter { it is KtFile || it is PsiJavaFile }
                .toList()
        val classes: List<PsiClass> = if (files.size == 1) {
            val psiFile = files
                    .firstNotNullOfOrNull {
                        when (it) {
                            is KtFile, is PsiJavaFile -> {
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
}