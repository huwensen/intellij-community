// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.base.fir.analysisApiProviders

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.PsiModificationTrackerImpl
import com.intellij.psi.impl.PsiTreeChangeEventImpl
import com.intellij.psi.impl.PsiTreeChangePreprocessor
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.getNonLocalReanalyzableContainingDeclaration
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.invalidateAfterInBlockModification
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.base.util.module
import org.jetbrains.kotlin.psi.KtDeclaration

@OptIn(LLFirInternals::class)
internal class FirIdeOutOfBlockPsiTreeChangePreprocessor(private val project: Project) : PsiTreeChangePreprocessor {
    override fun treeChanged(event: PsiTreeChangeEventImpl) {
        if (!PsiModificationTrackerImpl.canAffectPsi(event) ||
            event.isGenericChange ||
            event.code == PsiTreeChangeEventImpl.PsiEventType.BEFORE_CHILD_ADDITION
        ) {
            return
        }
        if (event.isGlobalChange()) {
            project.getService(FirIdeModificationTrackerService::class.java).increaseModificationCountForAllModules()
            return
        }

        val rootElement = event.parent
        val child = when (event.code) {
          PsiTreeChangeEventImpl.PsiEventType.CHILD_REMOVED -> rootElement
          PsiTreeChangeEventImpl.PsiEventType.BEFORE_CHILD_REPLACEMENT -> event.oldChild
          else -> event.child
        }

        if (rootElement == null || !rootElement.isPhysical) {
            /**
             * Element which do not belong to a project should not cause OOBM
             */
            return
        }

        val changeType = calculateChangeType(rootElement, child)
        when (changeType) {
            ChangeType.Invisible -> {}
            ChangeType.OutOfBlock -> {
                project.getService(FirIdeModificationTrackerService::class.java)
                    .increaseModificationCountForModuleAndProject(rootElement.module)
            }

            is ChangeType.InBlock -> {
                // trigger in-block a FIR tree invalidation
                invalidateAfterInBlockModification(changeType.blockOwner)
            }
        }

    }

    private fun calculateChangeType(rootElement: PsiElement, child: PsiElement?): ChangeType = when (rootElement.language) {
        KotlinLanguage.INSTANCE -> kotlinChangeType(child ?: rootElement)

        // TODO improve for Java KTIJ-21684
        JavaLanguage.INSTANCE -> ChangeType.OutOfBlock

        // Any other language may cause OOBM in Kotlin too
        else -> ChangeType.OutOfBlock
    }

    private fun kotlinChangeType(psi: PsiElement): ChangeType = when {
        // If PSI is not valid, well something bad happened, OOBM won't hurt
        !psi.isValid -> ChangeType.OutOfBlock
        psi is PsiWhiteSpace || psi is PsiComment -> ChangeType.Invisible
        else -> {
            val inBlockModificationOwner = psi.getNonLocalReanalyzableContainingDeclaration()
            inBlockModificationOwner?.let(ChangeType::InBlock) ?: ChangeType.OutOfBlock
        }
    }

    // Copy logic from PsiModificationTrackerImpl.treeChanged(). Some out-of-code-block events are written to language modification
    // tracker in PsiModificationTrackerImpl but don't have correspondent PomModelEvent. Increase kotlinOutOfCodeBlockTracker
    // manually if needed.
    private fun PsiTreeChangeEventImpl.isGlobalChange() = when (code) {
        PsiTreeChangeEventImpl.PsiEventType.PROPERTY_CHANGED ->
            propertyName === PsiTreeChangeEvent.PROP_UNLOADED_PSI || propertyName === PsiTreeChangeEvent.PROP_ROOTS
        PsiTreeChangeEventImpl.PsiEventType.CHILD_MOVED -> oldParent is PsiDirectory || newParent is PsiDirectory
        else -> parent is PsiDirectory
    }
}

private sealed class ChangeType {
    object OutOfBlock : ChangeType()
    object Invisible : ChangeType()
    class InBlock(val blockOwner: KtDeclaration) : ChangeType()
}