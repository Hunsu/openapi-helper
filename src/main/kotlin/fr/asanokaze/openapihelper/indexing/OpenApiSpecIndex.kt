package fr.asanokaze.openapihelper.indexing

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import fr.asanokaze.openapihelper.model.OpenApiComponent
import fr.asanokaze.openapihelper.model.OpenApiOperation
import fr.asanokaze.openapihelper.model.OpenApiTag
import fr.asanokaze.openapihelper.parsing.OpenApiYamlParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class IndexedOperation(
        val operationId: String,
        val path: String,
        val method: String,
        val tags: List<String> = emptyList(),
        val summary: String? = null,
        val filePath: String,
)

@Serializable
data class IndexedComponent(
        val name: String,
        val type: String?,
        val filePath: String,
)

@Serializable
data class IndexedTag(
        val name: String,
        val filePath: String,
)

/**
 * A file-based index extension for indexing OpenAPI YAML specifications in a single pass.
 */
class OpenApiSpecIndex : FileBasedIndexExtension<String, String>() {

    companion object {
        val KEY = ID.create<String, String>("fr.asanokaze.openapihelper.openapi.spec")
        private val json = Json { ignoreUnknownKeys = true }
    }

    override fun getName() = KEY
    override fun getVersion(): Int = 4
    override fun dependsOnFileContent(): Boolean = true

    override fun getIndexer(): DataIndexer<String, String, FileContent> = DataIndexer { fileContent ->
        val result = mutableMapOf<String, String>()

        if (!isOpenApiFile(fileContent)) {
            return@DataIndexer result
        }

        val filePath = fileContent.file.path
        val projectRelativePath = fileContent.file.path.removePrefix(
                fileContent.project.basePath.orEmpty()
        )

        OpenApiYamlParser.parse(filePath)
                .forEach {
                    when (it) {
                        is OpenApiOperation -> {
                            val operation = IndexedOperation(
                                    operationId = it.operationId,
                                    path = it.path,
                                    method = it.method.name,
                                    tags = it.tags,
                                    summary = it.summary,
                                    filePath = projectRelativePath
                            )
                            result["operation:${operation.operationId}"] = json.encodeToString(operation)
                        }

                        is OpenApiComponent -> {
                            val component = IndexedComponent(
                                    name = it.name,
                                    type = it.type,
                                    filePath = projectRelativePath
                            )
                            result["component:${it.name}"] = json.encodeToString(component)
                        }

                        is OpenApiTag -> {
                            val component = IndexedTag(
                                    name = it.name,
                                    filePath = projectRelativePath
                            )
                            result["tags:${it.name}"] = json.encodeToString(component)
                        }
                    }
                }
        result
    }

    private fun isOpenApiFile(fileContent: FileContent): Boolean {
        val extension = fileContent.file.extension?.lowercase()
        return extension in setOf("yaml", "yml", "json")
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer(): EnumeratorStringDescriptor = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter = FileBasedIndex.InputFilter { file ->
        val extension = file.extension?.lowercase()
        val projectFileIndex = ProjectFileIndex.getInstance(ProjectManager.getInstance().openProjects.first())
        !projectFileIndex.isInLibrary(file) && !file.path.contains("node_modules") &&
                extension in setOf("yaml", "yml", "json")
    }
}