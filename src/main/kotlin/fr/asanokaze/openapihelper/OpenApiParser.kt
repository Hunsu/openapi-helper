package fr.asanokaze.openapihelper

import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

class OpenApiParser {

    fun extractOperation(element: PsiElement): OpenApiOperation? {
        val keyValue = element as? YAMLKeyValue ?: return null

        if (isHttpMethod(keyValue.keyText)) {
            val pathMapping = keyValue.parent.parent as? YAMLKeyValue ?: return null
            return OpenApiOperation(
                    path = pathMapping.keyText,
                    method = keyValue.keyText,
                    tags = getTags(keyValue),
                    operationId = findOperationId(keyValue.value as? YAMLMapping) ?: return null,
                    summary = findSummary(keyValue.value as? YAMLMapping)
            )
        }

        if (keyValue.keyText == "operationId") {
            val methodMapping = keyValue.parent as? YAMLMapping ?: return null
            val methodKeyValue = methodMapping.parent as? YAMLKeyValue ?: return null
            val pathKeyValue = methodKeyValue.parent?.parent as? YAMLKeyValue ?: return null


            if (!isHttpMethod(methodKeyValue.keyText)) return null

            return OpenApiOperation(
                    path = pathKeyValue.keyText,
                    method = methodKeyValue.keyText,
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

    private fun isHttpMethod(key: String): Boolean {
        return key in listOf("get", "post", "put", "delete", "patch", "options", "head")
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

}