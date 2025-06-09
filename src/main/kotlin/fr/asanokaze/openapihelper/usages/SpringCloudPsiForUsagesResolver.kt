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
import org.jetbrains.kotlin.idea.base.util.projectScope
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

        LOG.debug("Found fileUrls: ${fileUrls.joinToString(",")}")
        val projectBasePath = project.basePath ?: ""
        val files = fileUrls.asSequence()
                .map { relativePath -> "file://$projectBasePath$relativePath" }
                .mapNotNull { fileUrl -> VirtualFileManager.getInstance().findFileByUrl(fileUrl) }
                .mapNotNull { virtualFile -> PsiManager.getInstance(project).findFile(virtualFile) }
        val classes: List<PsiClass> = getClasses(files)
        LOG.debug("Found classes: ${classes.joinToString(",") { it.qualifiedName ?: "" }}")
        val psiMethod = resolveMethod(openApiOperation, classes) ?: return emptyList()
        LOG.debug("Found method: ${psiMethod.name}")
        return listOf(psiMethod)
    }

    private fun resolveMethod(openApiOperation: OpenApiOperation, classes: List<PsiClass>): PsiMethod? {
        val clazz = classes.firstOrNull { klass ->
            val clientClass = JavaPsiFacade.getInstance(klass.project)
                    .findClass("${klass.qualifiedName}Client", klass.project.projectScope())
            LOG.debug("Found clientClass for ${klass.qualifiedName}: ${clientClass?.qualifiedName}")
            clientClass?.hasAnnotation("org.springframework.cloud.openfeign.FeignClient") == true
        }
        LOG.debug("Found : ${clazz?.qualifiedName}")
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

            LOG.debug("Found psiFile: $psiFile")
            if (psiFile != null) {
                val psiShortNamesCache = PsiShortNamesCache.getInstance(psiFile.project)
                val name = psiFile.name.removeSuffix(".kt").removeSuffix(".java")
                val classes = psiShortNamesCache.getClassesByName(name, GlobalSearchScope.projectScope(psiFile.project)).toList()
                LOG.debug("Found classes: ${classes.joinToString(",") { it.qualifiedName ?: "" }}")
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