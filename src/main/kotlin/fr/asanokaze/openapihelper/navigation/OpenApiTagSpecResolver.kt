package fr.asanokaze.openapihelper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import fr.asanokaze.openapihelper.indexing.IndexedTag
import fr.asanokaze.openapihelper.indexing.OpenApiSpecIndex
import fr.asanokaze.openapihelper.parsing.OpenApiYamlParser
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.psi.KtClass
import com.intellij.lang.javascript.psi.ecmal4.JSClass

/**
 * Resolver for finding OpenAPI tag specifications from generated API/controller classes.
 *
 * This resolver searches for tag definitions in OpenAPI specs by:
 * 1. Extracting potential tag names from class annotations or naming patterns
 * 2. Using OpenApiSpecIndex to find matching tags
 * 3. Navigating to the tag definition in the YAML file
 */
class OpenApiTagSpecResolver : OpenApiSpecResolver {

    private val LOG = Logger.getInstance(OpenApiTagSpecResolver::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override val resolverName: String = "tag-resolver"

    override fun resolveSpecElement(project: Project, element: PsiElement): List<PsiElement> {
        val tagNames = extractTagNames(element)
        if (tagNames.isEmpty()) return emptyList()

        LOG.debug("Searching for tag specs for names: $tagNames")

        val results = mutableListOf<PsiElement>()

        for (tagName in tagNames) {
            val tagKey = "tags:$tagName"
            val tagJsons = FileBasedIndex.getInstance()
                .getValues(OpenApiSpecIndex.KEY, tagKey, GlobalSearchScope.projectScope(project))

            for (tagJson in tagJsons) {
                try {
                    val indexedTag = json.decodeFromString<IndexedTag>(tagJson)
                    val specElement = findTagInSpec(project, indexedTag)
                    if (specElement != null) {
                        results.add(specElement)
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to deserialize tag: $tagJson", e)
                }
            }
        }

        LOG.debug("Found ${results.size} tag specs for names: $tagNames")
        return results
    }

    private fun extractTagNames(element: PsiElement): List<String> {
        return when (element) {
            is PsiClass -> extractTagNamesFromJavaClass(element)
            is KtClass -> extractTagNamesFromKotlinClass(element)
            is JSClass -> extractTagNamesFromTypeScriptClass(element)
            else -> emptyList()
        }
    }

    private fun extractTagNamesFromJavaClass(psiClass: PsiClass): List<String> {
        val tagNames = mutableListOf<String>()

        // Try to extract from @Tag annotation
        val tagAnnotation = psiClass.getAnnotation("io.swagger.v3.oas.annotations.tags.Tag")
        if (tagAnnotation != null) {
            val name = tagAnnotation.findAttributeValue("name")?.text?.trim('"')
            if (name != null) {
                tagNames.add(name)
            }
        }

        val className = psiClass.name
        className?.let {
            tagNames.addAll(getTagsFromName(it))
        }
        return tagNames
    }

    private fun extractTagNamesFromKotlinClass(ktClass: KtClass): List<String> {
        val tagNames = mutableListOf<String>()

        // Try to extract from @Tag annotation
        val tagAnnotation = ktClass.annotationEntries.find {
            it.name == "io.swagger.v3.oas.annotations.tags.Tag"
        }
        if (tagAnnotation != null) {
            val name = tagAnnotation.valueArguments.find { it.getArgumentName()?.asName?.asString() == "name" }
                ?.getArgumentExpression()?.text?.trim('"')
            if (name != null) {
                tagNames.add(name)
            }
        }

        // Fallback: derive from class name
        val className = ktClass.name
        className?.let {
            tagNames.addAll(getTagsFromName(it))
        }

        return tagNames
    }

    private fun getTagsFromName(className: String): List<String> {
        val tagNames = mutableListOf<String>()
        val tagName = className
            .removeSuffix("Controller")
            .removeSuffix("Delegate")
            .removeSuffix("Api")
        tagNames.add(tagName)
        tagNames.add(tagName.removeSuffix("Ws"))
        return tagNames
    }

    private fun extractTagNamesFromTypeScriptClass(jsClass: JSClass): List<String> {
        val tagNames = mutableListOf<String>()

        // For TypeScript classes, derive from class name
        val className = jsClass.name
        if (className != null) {
            val tagName = className
                .removeSuffix("Controller")
                .removeSuffix("Api")
                .removeSuffix("Client")
            tagNames.add(tagName)
        }

        return tagNames
    }

    private fun findTagInSpec(project: Project, tag: IndexedTag): PsiElement? {
        val projectBasePath = project.basePath ?: return null
        val fullPath = "file://$projectBasePath${tag.filePath}"

        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(fullPath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null

        // Find the specific tag in the YAML file
        return OpenApiYamlParser.findTagElement(psiFile, tag.name)
    }
}