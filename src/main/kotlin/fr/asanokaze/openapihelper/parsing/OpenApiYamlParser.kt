package fr.asanokaze.openapihelper.parsing

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import fr.asanokaze.openapihelper.model.*
import fr.asanokaze.openapihelper.model.HttpMethod.*
import fr.asanokaze.openapihelper.navigation.OpenApiComponentSpecResolver
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.parser.core.models.ParseOptions
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

class OpenApiYamlParser {

    companion object {

        private val LOG = Logger.getInstance(OpenApiComponentSpecResolver::class.java)
        private val parser = OpenAPIParser()

        fun parse(filePath: String): List<OpenApiElement> {
            val result = mutableListOf<OpenApiElement>()
            val options = ParseOptions()
            options.isResolve = true
            val openApiDocument = parser.readLocation(filePath, listOf(), options)
            openApiDocument.openAPI?.paths?.forEach { entry ->
                map(entry.key, GET, entry.value.get)?.let { result.add(it) }
                map(entry.key, POST, entry.value.post)?.let { result.add(it) }
                map(entry.key, PUT, entry.value.put)?.let { result.add(it) }
                map(entry.key, DELETE, entry.value.delete)?.let { result.add(it) }
                map(entry.key, HEAD, entry.value.head)?.let { result.add(it) }
                map(entry.key, TRACE, entry.value.trace)?.let { result.add(it) }
                map(entry.key, OPTIONS, entry.value.options)?.let { result.add(it) }
            }
            openApiDocument.openAPI?.tags?.forEach {
                result.add(OpenApiTag(it.name))
            }
            openApiDocument.openAPI?.components?.schemas?.forEach {
                result.add(OpenApiComponent(it.key, it.value.type))
            }

            return result
        }

        private fun map(path: String, method: HttpMethod, operation: Operation?): OpenApiOperation? {
            if (operation != null) {
                return OpenApiOperation(
                    path = path,
                    method = method,
                    tags = operation.tags,
                    operationId = operation.operationId,
                    summary = operation.summary,
                )
            }
            return null
        }


        fun isOpenApiFile(file: PsiFile): Boolean {
            return file.fileType is YAMLFileType
        }

        fun isHttpMethod(key: String): Boolean {
            return key in listOf("get", "post", "put", "delete", "patch", "options", "head")
        }

        fun isOperationId(key: String): Boolean {
            return key == "operationId"
        }

        fun extractOperation(element: PsiElement): OpenApiOperation? {
            val keyValue = element as? YAMLKeyValue ?: return null

            if (isHttpMethod(keyValue.keyText)) {
                val pathMapping = keyValue.parent.parent as? YAMLKeyValue ?: return null
                return OpenApiOperation(
                    path = pathMapping.keyText,
                    method = HttpMethod.from(keyValue.keyText),
                    tags = getTags(keyValue),
                    operationId = findOperationId(keyValue.value as? YAMLMapping) ?: return null,
                    summary = findSummary(keyValue.value as? YAMLMapping)
                )
            }

            if (isOperationId(keyValue.keyText)) {
                val methodMapping = keyValue.parent as? YAMLMapping ?: return null
                val methodKeyValue = methodMapping.parent as? YAMLKeyValue ?: return null
                val pathKeyValue = methodKeyValue.parent?.parent as? YAMLKeyValue ?: return null


                if (!isHttpMethod(methodKeyValue.keyText)) return null

                return OpenApiOperation(
                    path = pathKeyValue.keyText,
                    method = HttpMethod.from(methodKeyValue.keyText),
                    tags = getTags(methodKeyValue),
                    operationId = (keyValue.value as? YAMLScalar)?.textValue ?: return null,
                    summary = findSummary(methodMapping)
                )
            }

            return null
        }

        private fun findOperationId(mapping: YAMLMapping?): String? {
            if (mapping == null) return null
            val operationIdKeyValue = mapping.getKeyValueByKey("operationId") ?: return null
            return (operationIdKeyValue.value as? YAMLScalar)?.textValue
        }

        private fun getTags(psiElement: YAMLKeyValue): List<String> {
            val postMapping = psiElement.value as? YAMLMapping
            if (postMapping != null) {
                val tagsKeyValue = postMapping.getKeyValueByKey("tags")
                if (tagsKeyValue != null) {
                    val tagsSequence = tagsKeyValue.value as? YAMLSequence ?: return emptyList()
                    return tagsSequence.items.mapNotNull { item ->
                        (item?.value as? YAMLScalar)?.textValue
                    }
                }
            }

            val parentMapping = psiElement.parent as? YAMLMapping ?: return emptyList()
            val tagsKeyValue = parentMapping.getKeyValueByKey("tags") ?: return emptyList()
            val tagsSequence = tagsKeyValue.value as? YAMLSequence ?: return emptyList()

            return tagsSequence.items.mapNotNull { item ->
                (item?.value as? YAMLScalar)?.textValue
            }
        }

        private fun findSummary(mapping: YAMLMapping?): String? {
            if (mapping == null) return null
            val summaryKeyValue = mapping.getKeyValueByKey("summary") ?: return null
            return (summaryKeyValue.value as? YAMLScalar)?.textValue
        }

        /**
         * Finds a specific operation element in the OpenAPI YAML file
         */
        fun findOperationElement(psiFile: PsiFile, path: String, method: String, operationId: String): PsiElement? {
            LOG.debug("Searching for operation $operationId in path $path using method $method in file ${psiFile.name}")
            
            // First, check if the path has a $ref or direct operation
            LOG.debug("Looking for path element at paths$path")
            val pathElement = findElementByPath(psiFile, listOf("paths", path))
            LOG.debug("Path element: ${pathElement?.text}")
            if (pathElement is YAMLKeyValue) {
                LOG.debug("Found path element: ${pathElement.keyText}")
                val pathValue = pathElement.value
                if (pathValue is YAMLMapping) {
                    LOG.debug("Path value is a mapping with ${pathValue.keyValues.size} keys")
                    val refKeyValue = pathValue.getKeyValueByKey("\$ref")
                    if (refKeyValue != null) {
                        val refValue = (refKeyValue.value as? YAMLScalar)?.textValue
                        if (refValue != null) {
                            LOG.debug("Found \$ref in path $path: $refValue")
                            return followRefAndFindOperation(psiFile, refValue, method, operationId)
                        } else {
                            LOG.debug("Found \$ref key but value is null or not scalar")
                        }
                    } else {
                        LOG.debug("No \$ref found, checking for direct operation")
                        // Check for direct operation in this path
                        val methodKeyValue = pathValue.getKeyValueByKey(method.lowercase())
                        if (methodKeyValue != null) {
                            LOG.debug("Found method ${method.lowercase()} in path")
                            val methodMapping = methodKeyValue.value as? YAMLMapping
                            val operationIdKeyValue = methodMapping?.getKeyValueByKey("operationId")
                            if (operationIdKeyValue != null) {
                                val foundOperationId = (operationIdKeyValue.value as? YAMLScalar)?.textValue
                                LOG.debug("Found operationId: $foundOperationId, looking for: $operationId")
                                if (foundOperationId == operationId) {
                                    LOG.debug("Operation found in direct path!")
                                    return operationIdKeyValue
                                }
                            } else {
                                LOG.debug("No operationId found in method mapping")
                            }
                        } else {
                            LOG.debug("Method ${method.lowercase()} not found in path")
                        }
                    }
                } else {
                    LOG.debug("Path value is not a mapping: ${pathValue?.javaClass?.simpleName}")
                }
            } else {
                LOG.debug("Path element not found or not a YAMLKeyValue: ${pathElement?.javaClass?.simpleName}")
            }
            
            // Try to find operation in the standard paths structure
            LOG.debug("Trying standard location: paths/$path/${method.lowercase()}/operationId")
            val standardLocation = findElementByPath(psiFile, listOf("paths", path, method.lowercase(), "operationId"))
            if (standardLocation != null) {
                LOG.debug("Found operation in standard location")
                return standardLocation
            }
            LOG.debug("Operation not found in standard location")
            // If not found in standard location, search for operationId anywhere in the file
            // This handles cases where operations are defined in separate files and referenced
            LOG.debug("Searching for operationId anywhere in file")
            return findOperationIdAnywhere(psiFile, operationId)
        }

        /**
         * Follows a $ref and finds the operation in the referenced file
         */
        private fun followRefAndFindOperation(currentFile: PsiFile, refValue: String, method: String, operationId: String): PsiElement? {
            try {
                LOG.debug("Following \$ref: $refValue")
                // Parse the reference: './api-freelancer-compliance.yaml#/check-company-external-compliance'
                val parts = refValue.split("#")
                if (parts.size != 2) {
                    LOG.debug("Invalid \$ref format: $refValue - expected format 'file#fragment'")
                    return null
                }
                
                val filePath = parts[0]
                val fragmentPath = parts[1]
                LOG.debug("Parsed \$ref - file: '$filePath', fragment: '$fragmentPath'")
                
                // Resolve the file path relative to the current file
                val currentDir = currentFile.virtualFile?.parent
                if (currentDir == null) {
                    LOG.debug("Could not get parent directory of current file")
                    return null
                }
                LOG.debug("Current directory: ${currentDir.path}")
                
                val referencedFile = currentDir.findFileByRelativePath(filePath)
                if (referencedFile == null) {
                    LOG.debug("Referenced file not found: $filePath in directory ${currentDir.path}")
                    return null
                }
                LOG.debug("Found referenced file: ${referencedFile.path}")
                
                val referencedPsiFile = currentFile.manager.findFile(referencedFile)
                if (referencedPsiFile == null) {
                    LOG.debug("Could not create PSI file for: ${referencedFile.path}")
                    return null
                }
                
                LOG.debug("Following \$ref to file: ${referencedFile.path}, fragment: $fragmentPath")
                
                // Navigate to the fragment path (e.g., /check-company-external-compliance)
                val fragmentSegments = fragmentPath.removePrefix("/").split("/").filter { it.isNotEmpty() }
                LOG.debug("Fragment segments: $fragmentSegments")
                val referencedElement = findElementByPath(referencedPsiFile, fragmentSegments)
                
                if (referencedElement is YAMLMapping) {
                    LOG.debug("Referenced element is a YAMLMapping with ${referencedElement.keyValues.size} keys")
                    // Look for the method and operationId in the referenced element
                    val methodKeyValue = referencedElement.getKeyValueByKey(method.lowercase())
                    if (methodKeyValue != null) {
                        LOG.debug("Found method ${method.lowercase()} in referenced element")
                        val methodMapping = methodKeyValue.value as? YAMLMapping
                        val operationIdKeyValue = methodMapping?.getKeyValueByKey("operationId")
                        if (operationIdKeyValue != null) {
                            val foundOperationId = (operationIdKeyValue.value as? YAMLScalar)?.textValue
                            LOG.debug("Found operationId in referenced file: $foundOperationId, looking for: $operationId")
                            if (foundOperationId == operationId) {
                                LOG.debug("Operation found in referenced file!")
                                return operationIdKeyValue
                            }
                        } else {
                            LOG.debug("No operationId found in method mapping in referenced file")
                        }
                    } else {
                        LOG.debug("Method ${method.lowercase()} not found in referenced element")
                    }
                } else {
                    LOG.debug("Referenced element is not a YAMLMapping: ${referencedElement?.javaClass?.simpleName}")
                }
                
                // If not found in the specific path, search anywhere in the referenced file
                LOG.debug("Searching for operationId anywhere in referenced file")
                return findOperationIdAnywhere(referencedPsiFile, operationId)
                
            } catch (e: Exception) {
                LOG.debug("Error following \$ref: $refValue", e)
                return null
            }
        }

        /**
         * Finds a specific component element in the OpenAPI YAML file
         */
        fun findComponentElement(psiFile: PsiFile, componentName: String): PsiElement? {
            // Navigate to components > schemas > {componentName}
            return findElementByPath(psiFile, listOf("components", "schemas", componentName))
        }

        /**
         * Finds a specific tag element in the OpenAPI YAML file
         */
        fun findTagElement(psiFile: PsiFile, tagName: String): PsiElement? {
            // Navigate to tags array
            val tagsKeyValue = findElementByPath(psiFile, listOf("tags"))
            if (tagsKeyValue !is YAMLKeyValue) return null
            
            val tagsSequence = tagsKeyValue.value as? YAMLSequence ?: return null
            
            // Traverse the tags array to find the matching tag
            for (item in tagsSequence.items) {
                val tagMapping = item?.value as? YAMLMapping ?: continue
                
                // Look for the "name" key in the tag object
                val nameKeyValue = tagMapping.getKeyValueByKey("name")
                if (nameKeyValue != null) {
                    val nameValue = (nameKeyValue.value as? YAMLScalar)?.textValue
                    if (nameValue.equals(tagName, ignoreCase = true)) {
                        return nameKeyValue
                    }
                }
            }
            
            return null
        }

        /**
         * Helper method to find YAML elements by path
         */
        private fun findElementByPath(psiFile: PsiFile, pathSegments: List<String>): PsiElement? {
            var current: PsiElement? = psiFile.firstChild

            for ((index, segment) in pathSegments.withIndex()) {
                current = findChildKeyValue(current, segment)
                if (current == null) return null
                
                // For the last segment, return the YAMLKeyValue itself
                // For intermediate segments, move to the value part
                if (index < pathSegments.size - 1) {
                    current = current.value
                }
            }

            return current
        }

        /**
         * Helper method to find a child key-value pair by key name
         */
        private fun findChildKeyValue(parent: PsiElement?, keyName: String): YAMLKeyValue? {
            if (parent == null) return null

            // Recursively search through children
            var child = parent.firstChild
            while (child != null) {
                if (child is YAMLKeyValue && child.keyText == keyName) {
                    return child
                }
                // Recursively search in children
                val found = findChildKeyValue(child, keyName)
                if (found != null) return found

                child = child.nextSibling
            }

            return null
        }

        /**
         * Searches for an operationId anywhere in the YAML file
         */
        private fun findOperationIdAnywhere(psiFile: PsiFile, operationId: String): PsiElement? {
            LOG.debug("Searching for operation $operationId anywhere in the YAML file ${psiFile.name}")
            return findOperationIdInElement(psiFile, operationId)
        }

        /**
         * Recursively searches for an operationId key with the given value
         */
        private fun findOperationIdInElement(element: PsiElement, operationId: String): PsiElement? {
            // If this element is a YAMLKeyValue with key "operationId"
            if (element is YAMLKeyValue && element.keyText == "operationId") {
                val value = (element.value as? YAMLScalar)?.textValue
                if (value == operationId) {
                    return element
                }
            }

            // Recursively search all children
            var child = element.firstChild
            while (child != null) {
                val found = findOperationIdInElement(child, operationId)
                if (found != null) return found
                child = child.nextSibling
            }

            return null
        }
    }
}