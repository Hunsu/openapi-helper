package fr.asanokaze.openapihelper

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import com.intellij.util.concurrency.AppExecutorUtil
import fr.asanokaze.openapihelper.parsing.OpenApiYamlParser
import fr.asanokaze.openapihelper.usages.OpenApiPsiForUsagesResolversFactory
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLPsiElement
import org.jetbrains.yaml.psi.YAMLScalar
import java.util.*

private val LOG = Logger.getInstance(OpenApiOperationIdUsageSearcher::class.java)

class OpenApiOperationIdUsageSearcher : PsiReferenceContributor() {


    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLKeyValue::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val yamlKeyValue = element as? YAMLKeyValue ?: return PsiReference.EMPTY_ARRAY
                val keyText = yamlKeyValue.keyText

                if (!OpenApiYamlParser.isOperationId(keyText)) return PsiReference.EMPTY_ARRAY

                val operationIdValue = (yamlKeyValue.value as? YAMLScalar)?.textValue ?: return PsiReference.EMPTY_ARRAY

                LOG.debug("Will search for usages of operationId: $operationIdValue")

                val rangeInElement = yamlKeyValue.value?.textRange?.shiftLeft(yamlKeyValue.textRange.startOffset)
                        ?: TextRange(0, keyText.length)

                return arrayOf(OperationIdReference(yamlKeyValue, rangeInElement))
            }
        })
    }
}

class OperationIdReference(element: YAMLPsiElement, range: TextRange) : PsiReferenceBase<PsiElement>(element, range), PsiPolyVariantReference {
    private val cachedResults = WeakHashMap<PsiElement, PsiElement?>()

    override fun resolve(): PsiElement? {
        if (DumbService.isDumb(element.project)) return null

        ReadAction.nonBlocking<PsiElement?> {
            val operation = OpenApiYamlParser.extractOperation(element) ?: return@nonBlocking null
            val referenceElement = OpenApiPsiForUsagesResolversFactory.resolvers().flatMap {
                it.resolve(element.project, operation)
            }.firstOrNull() ?: return@nonBlocking null
            cachedResults[element] = referenceElement
            return@nonBlocking referenceElement
        }.inSmartMode(element.project)
                .submit(AppExecutorUtil.getAppExecutorService())

        return cachedResults[element]
    }

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult?> {
        return ResolveResult.EMPTY_ARRAY
    }
}