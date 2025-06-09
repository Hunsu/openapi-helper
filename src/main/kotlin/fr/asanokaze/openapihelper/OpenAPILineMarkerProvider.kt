package fr.asanokaze.openapihelper

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Function
import com.intellij.util.PsiNavigateUtil
import com.intellij.util.indexing.FileBasedIndex
import fr.asanokaze.openapihelper.indexing.KEY
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLKeyValue
import javax.swing.Icon
import javax.swing.JList

private val LOG = Logger.getInstance(OpenAPILineMarkerProvider::class.java)

class OpenAPILineMarkerProvider : LineMarkerProvider {


    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        // Only process leaf elements that are key identifiers
        if (element.node?.elementType != YAMLTokenTypes.SCALAR_KEY) return null

        val file = element.containingFile
        if (!isOpenApiFile(file)) return null

        // Get the parent YAMLKeyValue
        val keyValue = element.parent as? YAMLKeyValue ?: return null
        val key = keyValue.keyText

        // Add gutter icons for "paths" or HTTP methods
        if (key == "paths" || isHttpMethod(key) || isOperationId(key)) {
            val icon: Icon = AllIcons.Gutter.ImplementedMethod
            val tooltip = "Navigate to implementation"

            return LineMarkerInfo(
                    element,
                    element.textRange,
                    icon,
                    Function { tooltip },
                    { e, elt -> navigateToImplementation(keyValue) }, // Note: passing keyValue here
                    GutterIconRenderer.Alignment.LEFT,
                    { "Navigate to implementation" }
            )
        }

        return null
    }

    private fun isOperationId(key: String): Boolean {
        return key == "operationId"
    }

    private fun isOpenApiFile(file: PsiFile): Boolean {
        return file.fileType is YAMLFileType
    }

}

private fun isHttpMethod(key: String): Boolean {
    return key in listOf("get", "post", "put", "delete", "patch", "options", "head")
}

private fun navigateToImplementation(element: PsiElement) {
    val openApiOperation = OpenApiParser().extractOperation(element) ?: return
    val implementations = findTargetClassOrMethod(element, openApiOperation)

    if (implementations.isEmpty()) return

    // Create navigation handler
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

    // Trigger the navigation
    navigationHandler.navigate(null, element)
}


private fun findTargetClassOrMethod(element: PsiElement, operationRef: OpenApiOperation): List<PsiElement> {
    val operationId = operationRef.operationId
    val fileUrls = FileBasedIndex.getInstance()
            .getValues(KEY, operationId, GlobalSearchScope.projectScope(element.project))

    fileUrls.asSequence() // Use sequence for lazy evaluation
            .mapNotNull { fileUrl -> VirtualFileManager.getInstance().findFileByUrl(fileUrl) }
            .mapNotNull { virtualFile -> PsiManager.getInstance(element.project).findFile(virtualFile) }
            .forEach { psiFile ->
                val result = when (psiFile) {
                    is PsiJavaFile -> processJavaFile(psiFile, operationRef)
                    is KtFile -> processKotlinFile(psiFile, operationRef)
                    else -> null
                }
                if (result != null) {
                    return listOf(result)
                }
            }
    return emptyList()
}

private fun processJavaFile(psiFile: PsiJavaFile, operationRef: OpenApiOperation): PsiMethod? {
    val restControllerClass = psiFile.classes.firstOrNull { it.hasAnnotation("org.springframework.web.bind.annotation.RestController") }
    return restControllerClass?.let { findMethodWithOperation(it, operationRef) }
}

private fun processKotlinFile(psiFile: KtFile, operationRef: OpenApiOperation): PsiElement? {
    val restControllerClass = psiFile.classes.firstOrNull {
        it.hasAnnotation("org.springframework.web.bind.annotation.RestController") ||
                it.hasAnnotation("rg.springframework.web.bind.annotation..RequestMapping") ||
                it.methods.any { it.name == "getDelegate" }
    }
    return restControllerClass?.let { findMethodWithOperation(it, operationRef) }
}

private fun findMethodWithOperation(psiClass: PsiClass, operation: OpenApiOperation): PsiMethod? {
    var method: PsiMethod? = null
    val module = ModuleUtilCore.findModuleForPsiElement(psiClass)
    val scope = (module?.moduleWithDependentsScope
            ?: GlobalSearchScope.projectScope(psiClass.project))

    LOG.info("Searching for implementation of $operation in ${psiClass.qualifiedName} using scope $scope")
    method = findMethodInClass(psiClass, operation) ?: return null
    if (psiClass.methods.any { it.name == "getDelegate" }) {
        val delegateClass = JavaPsiFacade.getInstance(psiClass.project)
                .findClass(psiClass.qualifiedName + "Delegate", scope)

        if (delegateClass != null) {
            method = delegateClass.findMethodBySignature(method, false) ?: method
        }
    }
    // Check for overriding methods
    LOG.info("Found implementation of $operation: $method")
    LOG.info("Searching for overriding methods of $method using scope $scope")
    val overridingMethods = OverridingMethodsSearch.search(method, scope, false).findAll()
    LOG.info("Found overriding methods: ${overridingMethods.joinToString(", ")}")
    if (overridingMethods.isNotEmpty()) {
        return overridingMethods.first()
    }

    return method
}

private fun findMethodInClass(psiClass: PsiClass, operation: OpenApiOperation): PsiMethod? {
    val method = psiClass.methods.find { method ->
        // Find @Operation annotation
        val operationAnnotation = method.getAnnotation("io.swagger.v3.oas.annotations.Operation")

        // Find @RequestMapping annotation (or specific HTTP method annotations)
        val requestMappingAnnotation = method.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
                ?: method.getAnnotation("org.springframework.web.bind.annotation.PutMapping")
                ?: method.getAnnotation("org.springframework.web.bind.annotation.PostMapping")
                ?: method.getAnnotation("org.springframework.web.bind.annotation.GetMapping")
                ?: method.getAnnotation("org.springframework.web.bind.annotation.DeleteMapping")
                ?: method.getAnnotation("org.springframework.web.bind.annotation.PatchMapping")

        if (operationAnnotation == null || requestMappingAnnotation == null) return@find false

        // Compare operationId
        val operationId = operationAnnotation.findAttributeValue("operationId")?.text?.trim('"')
        if (operationId != operation.operationId) return@find false

        // Compare summary if present
        val summary = operationAnnotation.findAttributeValue("summary")?.text?.trim('"')
        if (operation.summary != null && summary != operation.summary) return@find false

        // Compare tags
        val tags = (operationAnnotation.findAttributeValue("tags") as? PsiArrayInitializerMemberValue)
                ?.initializers
                ?.mapNotNull { (it as? PsiLiteralExpression)?.value as? String }
                ?: emptyList()
        if (!tags.containsAll(operation.tags)) return@find false

        // Compare HTTP method
        val httpMethodAttribute = requestMappingAnnotation.findAttributeValue("method")
        val method = when (httpMethodAttribute) {
            is PsiReferenceExpression -> httpMethodAttribute.referenceName?.replace("RequestMethod.", "")
            is PsiArrayInitializerMemberValue -> httpMethodAttribute.initializers.firstOrNull()?.text?.replace("org.springframework.web.bind.annotation.RequestMethod.", "")
            else -> null
        }
        val httpPathAttribute = requestMappingAnnotation.findAttributeValue("value")
        val path = when (httpPathAttribute) {
            is PsiReferenceExpression -> httpPathAttribute.referenceName
            is PsiArrayInitializerMemberValue -> httpPathAttribute.initializers.firstOrNull()?.text?.unquote()
            else -> null
        }

        path != null && method != null && method.equals(operation.method, ignoreCase = true) &&
                (path == operation.path || !operation.path.startsWith("/"))
    } ?: return null

    return if (method.name.startsWith('_')) {
        val methodNameWithoutUnderscore = method.name.substring(1)
        psiClass.findMethodsByName(methodNameWithoutUnderscore, false).firstOrNull() ?: method
    } else method
}

private fun String?.unquote(): String? {
    if (this == null) return null

    return when {
        (this.startsWith('"') && this.endsWith('"')) ||
                (this.startsWith('\'') && this.endsWith('\'')) -> {
            this.substring(1, this.length - 1)
        }

        else -> this
    }
}

