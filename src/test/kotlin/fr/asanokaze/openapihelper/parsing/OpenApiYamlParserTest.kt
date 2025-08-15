package fr.asanokaze.openapihelper.parsing

import fr.asanokaze.openapihelper.model.OpenApiComponent
import fr.asanokaze.openapihelper.model.OpenApiOperation
import fr.asanokaze.openapihelper.model.OpenApiTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenApiYamlParserTest {

    @Test
    fun `should parse open api file`() {

        val openApiElements = OpenApiYamlParser.parse("src/test/resources/openapi.yaml")

        assertEquals(3, openApiElements.filter { it is OpenApiTag }.size)
        assertEquals(19, openApiElements.filter { it is OpenApiOperation }.size)
        assertEquals(6, openApiElements.filter { it is OpenApiComponent }.size)
    }
}