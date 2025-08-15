package fr.asanokaze.openapihelper.navigation

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Function
import com.intellij.util.PsiNavigateUtil
import fr.asanokaze.openapihelper.parsing.OpenApiYamlParser
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLKeyValue
import javax.swing.Icon
import javax.swing.JList

class OpenAPILineMarkerProvider : LineMarkerProvider {

    private val resolvers = ImplementationResolversFactory.resolvers()

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        if (element.node?.elementType != YAMLTokenTypes.SCALAR_KEY) return null

        val file = element.containingFile
        if (!OpenApiYamlParser.isOpenApiFile(file)) return null

        val keyValue = element.parent as? YAMLKeyValue ?: return null
        val key = keyValue.keyText

        if (OpenApiYamlParser.isHttpMethod(key) || OpenApiYamlParser.isOperationId(key)) {
            val icon: Icon = AllIcons.Gutter.ImplementedMethod
            val tooltip = "Navigate to implementation"

            return LineMarkerInfo(
                    element,
                    element.textRange,
                    icon,
                    Function { tooltip },
                    { _, _ -> navigateToImplementation(keyValue) }, // Note: passing keyValue here
                    GutterIconRenderer.Alignment.LEFT,
                    { "Navigate to implementation" }
            )
        }

        return null
    }

    private fun navigateToImplementation(element: PsiElement) {
        val openApiOperation = OpenApiYamlParser.extractOperation(element) ?: return
        val implementations = resolvers.flatMap { it.resolveImplementation(element.project, openApiOperation) }

        if (implementations.isEmpty()) return

        val navigationHandler = GutterIconNavigationHandler<PsiElement> { mouseEvent, _ ->
            if (implementations.size == 1) {
                PsiNavigateUtil.navigate(implementations.first())
            } else {
                // If there are multiple implementations, show a popup list
                val popup = JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(implementations)
                        .setTitle("Choose Implementation")
                        .setItemChosenCallback { element ->
                            PsiNavigateUtil.navigate(element)
                        }
                        .setRenderer(object : ColoredListCellRenderer<PsiElement>() {
                            override fun customizeCellRenderer(
                                    list: JList<out PsiElement>,
                                    value: PsiElement,
                                    index: Int,
                                    selected: Boolean,
                                    hasFocus: Boolean
                            ) {
                                when (value) {
                                    is PsiMethod -> {
                                        append(value.name)
                                        append(" in ")
                                        append(value.containingClass?.qualifiedName ?: "")
                                    }

                                    else -> append(value.text)
                                }
                            }
                        })
                        .createPopup()

                popup.show(RelativePoint(mouseEvent))
            }
        }
        navigationHandler.navigate(null, element)
    }
}