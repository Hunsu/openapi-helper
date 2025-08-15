package fr.asanokaze.openapihelper.usages

import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import fr.asanokaze.openapihelper.indexing.KEY
import fr.asanokaze.openapihelper.model.OpenApiOperation

class TypeScriptPsiForUsagesResolver : OpenApiPsiForUsagesResolver {

    private val LOG = Logger.getInstance(TypeScriptPsiForUsagesResolver::class.java)

    override val generatorName: String
        get() = "typescript"

    override fun resolve(project: Project, openApiOperation: OpenApiOperation): List<PsiElement> {
        val fileUrls = FileBasedIndex.getInstance()
                .getValues(KEY, openApiOperation.operationId, GlobalSearchScope.projectScope(project))

        LOG.debug("Found fileUrls: ${fileUrls.joinToString(",")}")
        val projectBasePath = project.basePath ?: ""
        return fileUrls.asSequence()
                .map { relativePath -> "file://$projectBasePath$relativePath" }
                .mapNotNull { fileUrl -> VirtualFileManager.getInstance().findFileByUrl(fileUrl) }
                .filter { it.extension == "ts" }
                .mapNotNull { virtualFile -> PsiManager.getInstance(project).findFile(virtualFile) }
                .mapNotNull {
                    val classes = PsiTreeUtil.getChildrenOfTypeAsList(it, JSClass::class.java)
                    resolveFunction(classes, openApiOperation)
                }.toList()
    }

    private fun resolveFunction(
            classes: List<JSClass>,
            operation: OpenApiOperation
    ): JSFunction? {
        val pathRegex = Regex("""path\s*:\s*["'`](.*?)["'`]""")
        val methodRegex = Regex("""method\s*:\s*["'`](.*?)["'`]""")
        LOG.debug("Lets check ${classes.map { it.name }} for $operation")
        for (jsClass in classes) {
            val functions = jsClass.functions
            val rawFunction = functions.find { it.name == "${operation.operationId}Raw" }

            if (rawFunction != null) {
                LOG.debug("Found \"${operation.operationId}Raw\" function for ${jsClass.name}")
                val functionBodyText = rawFunction.text
                val pathMatch = pathRegex.find(functionBodyText)?.groups?.get(1)?.value
                val methodMatch = methodRegex.find(functionBodyText)?.groups?.get(1)?.value
                if ((pathMatch == operation.path || !operation.path.startsWith("/")) &&
                        methodMatch.equals(operation.method.name, ignoreCase = true)) {
                    val nonRawFunction = functions.find { it.name == operation.operationId }
                    if (nonRawFunction != null) {
                        LOG.debug("${operation.operationId}Raw function has been replaced by ${nonRawFunction.name}")
                        return nonRawFunction
                    }

                    return rawFunction
                } else {
                    LOG.debug("${rawFunction.name} doesn't match the operationId and path/method")
                }
            } else {
                LOG.debug("Didn't find \"${operation.operationId}Raw\" function for ${jsClass.name}")
            }
        }
        return null
    }
}