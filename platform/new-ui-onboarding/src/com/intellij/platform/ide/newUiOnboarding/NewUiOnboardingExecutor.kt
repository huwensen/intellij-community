// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.newUiOnboarding

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.JComponent

internal class NewUiOnboardingExecutor(private val project: Project,
                                       private val steps: List<NewUiOnboardingStep>,
                                       private val cs: CoroutineScope,
                                       parentDisposable: Disposable) {
  private val disposable = Disposer.newDisposable()

  init {
    Disposer.register(parentDisposable, disposable)
  }

  suspend fun start() {
    runStep(0)
  }

  private suspend fun runStep(ind: Int) {
    if (ind >= steps.size) {
      return
    }
    val step = steps[ind]
    val gotItData = step.performStep(project)
    if (gotItData == null) {
      runStep(ind + 1)
      return
    }

    val showInCenter = gotItData.position == null
    val builder = gotItData.builder
    builder.withStepNumber("${ind + 1}/${steps.size}")

    if (ind < steps.lastIndex) {
      builder.withButtonLabel(NewUiOnboardingBundle.message("gotIt.button.next"))
        .onButtonClick {
          cs.launch(Dispatchers.EDT) { runStep(ind + 1) }
        }
        .withSecondaryButton(NewUiOnboardingBundle.message("gotIt.button.skipAll")) {
          Disposer.dispose(disposable)
        }
    }
    else {
      builder.withButtonLabel(NewUiOnboardingBundle.message("gotIt.button.done"))
        .withContrastButton(true)
        .onButtonClick {
          Disposer.dispose(disposable)
        }
    }

    val balloon = builder.build(disposable) {
      // do not show the pointer if the balloon should be centered
      setShowCallout(!showInCenter)
    }

    if (showInCenter) {
      balloon.showInCenterOf(gotItData.relativePoint.originalComponent as JComponent)
    }
    else {
      balloon.show(gotItData.relativePoint, gotItData.position)
    }
  }
}