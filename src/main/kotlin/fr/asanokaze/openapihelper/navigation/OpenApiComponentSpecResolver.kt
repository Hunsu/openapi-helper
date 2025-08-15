package fr.asanokaze.openapihelper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import fr.asanokaze.openapihelper.indexing.IndexedComponent
import fr.asanokaze.openapihelper.indexing.OpenApiSpecIndex
import fr.asanokaze.openapihelper.parsing.OpenApiYamlParser
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.psi.KtClass
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Resolver for finding OpenAPI component specifications from generated model classes.
 * 
 * This resolver searches for component/schema definitions in OpenAPI specs by:
 * 1. Extracting the class name as potential component name
 * 2. Using OpenApiSpecIndex to find matching components
 * 3. Navigating to the component definition in the YAML file
 */
class OpenApiComponentSpecResolver : OpenApiSpecResolver {

    private val LOG = Logger.getInstance(OpenApiComponentSpecResolver::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override val resolverName: String = "component-resolver"

    override fun resolveSpecElement(project: Project, element: PsiElement): List<PsiElement> {
        val componentName = extractComponentName(element) ?: return emptyList()
        
        LOG.debug("Searching for component spec for name: $componentName")
        
        val componentKey = "component:$componentName"
        val componentJsons = FileBasedIndex.getInstance()
            .getValues(OpenApiSpecIndex.KEY, componentKey, GlobalSearchScope.allScope(project))
        
        if (componentJsons.isEmpty()) {
            LOG.debug("No component found for name: $componentName")
            return emptyList()
        }
        
        val results = mutableListOf<PsiElement>()
        
        for (componentJson in componentJsons) {
            try {
                val indexedComponent = json.decodeFromString<IndexedComponent>(componentJson)
                val specElement = findComponentInSpec(project, indexedComponent)
                if (specElement != null) {
                    results.add(specElement)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to deserialize component: $componentJson", e)
            }
        }
        
        LOG.debug("Found ${results.size} component specs for name: $componentName")
        return results
    }

    private fun extractComponentName(element: PsiElement): String? {
        return when (element) {
            is PsiClass -> element.name
            is KtClass -> element.name
            is JSClass -> element.name
            else -> {
                LOG.warn("Unsupported element type: ${element.javaClass}")
                null
            }
        }
    }

    private fun findComponentInSpec(project: Project, component: IndexedComponent): PsiElement? {
        val projectBasePath = project.basePath ?: return null
        val fullPath = "file://$projectBasePath${component.filePath}"
        
        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(fullPath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        
        // Find the specific component in the YAML file
        val componentElement = OpenApiYamlParser.findComponentElement(psiFile, component.name)
        if (componentElement != null) {
            return componentElement
        }
        
        // If component not found locally, search in referenced files
        LOG.debug("Component ${component.name} not found locally, searching in referenced files")
        return searchComponentInReferencedFiles(psiFile, component.name)
    }

    /**
     * Searches for a component in files referenced by $ref in the current file
     */
    private fun searchComponentInReferencedFiles(psiFile: PsiFile, componentName: String): PsiElement? {
        val referencedFiles = findAllReferencedFiles(psiFile)
        
        for (referencedFile in referencedFiles) {
            LOG.debug("Searching for component $componentName in referenced file: ${referencedFile.name}")
            val componentElement = OpenApiYamlParser.findComponentElement(referencedFile, componentName)
            if (componentElement != null) {
                LOG.debug("Found component $componentName in referenced file: ${referencedFile.name}")
                return componentElement
            }
        }
        
        LOG.debug("Component $componentName not found in any referenced files")
        return null
    }

    /**
     * Finds all files referenced by $ref in the current YAML file
     */
    private fun findAllReferencedFiles(psiFile: PsiFile): List<PsiFile> {
        val referencedFiles = mutableListOf<PsiFile>()
        val processedFiles = mutableSetOf<String>()
        
        findReferencedFilesRecursively(psiFile, referencedFiles, processedFiles)
        
        return referencedFiles
    }

    /**
     * Recursively finds all $ref references that point to external files
     */
    private fun findReferencedFilesRecursively(element: PsiElement, referencedFiles: MutableList<PsiFile>, processedFiles: MutableSet<String>) {
        if (element is YAMLKeyValue && element.keyText == "\$ref") {
            val refValue = (element.value as? YAMLScalar)?.textValue
            LOG.debug("Found \$ref in file ${element.containingFile.name}: $refValue")
            if (refValue != null && refValue.contains("#")) {
                val filePath = refValue.split("#")[0]
                if (filePath.isNotEmpty() && !processedFiles.contains(filePath)) {
                    processedFiles.add(filePath)
                    
                    // Resolve the file path relative to the current file
                    val currentDir = element.containingFile.virtualFile?.parent
                    val referencedVirtualFile = currentDir?.findFileByRelativePath(filePath)
                    
                    if (referencedVirtualFile != null) {
                        val referencedPsiFile = element.manager.findFile(referencedVirtualFile)
                        if (referencedPsiFile != null) {
                            LOG.debug("Found referenced file: ${referencedVirtualFile.path}")
                            referencedFiles.add(referencedPsiFile)
                            
                            // Recursively search in the referenced file for more $refs
                            findReferencedFilesRecursively(referencedPsiFile, referencedFiles, processedFiles)
                        }
                    } else {
                        LOG.debug("Referenced file not found: $filePath")
                    }
                }
            }
        }
        
        // Recursively search all children
        var child = element.firstChild
        while (child != null) {
            findReferencedFilesRecursively(child, referencedFiles, processedFiles)
            child = child.nextSibling
        }
    }
}