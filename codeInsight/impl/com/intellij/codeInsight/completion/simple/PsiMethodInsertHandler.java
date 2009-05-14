/*
 * Copyright (c) 2000-2005 by JetBrains s.r.o. All Rights Reserved.
 * Use is subject to license terms.
 */
package com.intellij.codeInsight.completion.simple;

import com.intellij.codeInsight.*;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.completion.util.MethodParenthesesHandler;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupItem;
import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author peter
*/
public class PsiMethodInsertHandler implements InsertHandler<LookupItem<PsiMethod>> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.completion.simple.PsiMethodInsertHandler");
  private final PsiMethod myMethod;

  public PsiMethodInsertHandler(final PsiMethod method) {
    myMethod = method;
  }

  public void handleInsert(final InsertionContext context, final LookupItem<PsiMethod> item) {
    context.setAddCompletionChar(false);
    final Editor editor = context.getEditor();
    final char completionChar = context.getCompletionChar();
    TailType tailType = getTailType(item, editor, completionChar);
    final Document document = editor.getDocument();
    final PsiFile file = context.getFile();

    final LookupElement[] allItems = context.getElements();
    boolean signatureSelected = allItems.length > 1 && CodeInsightSettings.getInstance().SHOW_SIGNATURES_IN_LOOKUPS ||
                                item.getUserData(LookupItem.FORCE_SHOW_SIGNATURE_ATTR) != null;

    int offset = editor.getCaretModel().getOffset();
    final boolean needLeftParenth = isToInsertParenth(file.findElementAt(context.getStartOffset()));
    final boolean hasParams = MethodParenthesesHandler.hasParams(item, allItems, !signatureSelected, myMethod);
    if (needLeftParenth) {
      final CodeStyleSettings styleSettings = CodeStyleSettingsManager.getSettings(context.getProject());
      new MethodParenthesesHandler(myMethod, !signatureSelected,
                                           styleSettings.SPACE_BEFORE_METHOD_CALL_PARENTHESES,
                                           styleSettings.SPACE_WITHIN_METHOD_CALL_PARENTHESES && hasParams,
                                           shouldInsertRightParenthesis(hasParams, tailType)
      ).handleInsert(context, item);
    }
    
    insertExplicitTypeParams(item, document, offset, file);

    final PsiType type = myMethod.getReturnType();
    if (completionChar == '!' && type != null && PsiType.BOOLEAN.isAssignableFrom(type)) {
      PsiDocumentManager.getInstance(myMethod.getProject()).commitDocument(document);
      final PsiMethodCallExpression methodCall =
          PsiTreeUtil.findElementOfClassAtOffset(file, offset, PsiMethodCallExpression.class, false);
      if (methodCall != null) {
        FeatureUsageTracker.getInstance().triggerFeatureUsed(CodeCompletionFeatures.EXCLAMATION_FINISH);
        document.insertString(methodCall.getTextRange().getStartOffset(), "!");
      }
    }

    if (needLeftParenth && hasParams) {
      // Invoke parameters popup
      AutoPopupController.getInstance(myMethod.getProject()).autoPopupParameterInfo(editor, signatureSelected ? myMethod : null);
    }
    tailType.processTail(editor, context.getTailOffset());
    editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
  }

  protected static boolean shouldInsertRightParenthesis(boolean hasParams, TailType tailType) {
    if (tailType == TailType.SMART_COMPLETION) return false;

    final CodeInsightSettings settings = CodeInsightSettings.getInstance();
    if (settings.INSERT_SINGLE_PARENTH && (!settings.INSERT_DOUBLE_PARENTH_WHEN_NO_ARGS || hasParams) && tailType == TailType.NONE) {
      return false;
    }
    return true;
  }

  @NotNull
  private static TailType getTailType(final LookupItem item, final Editor editor, final char completionChar) {
    if (completionChar == '!') return item.getTailType();
    if (completionChar == '(') {
      final PsiMethod psiMethod = (PsiMethod)item.getObject();
      return psiMethod.getParameterList().getParameters().length > 0 || psiMethod.getReturnType() != PsiType.VOID
             ? TailType.NONE : TailType.SEMICOLON;
    }
    if (completionChar == Lookup.COMPLETE_STATEMENT_SELECT_CHAR) return TailType.SMART_COMPLETION;
    return LookupItem.handleCompletionChar(editor, item, completionChar);
  }

  private static boolean isToInsertParenth(PsiElement place){
    if (place == null) return true;
    return !(place.getParent() instanceof PsiImportStaticReferenceElement);
  }

  private static void insertExplicitTypeParams(final LookupItem<PsiMethod> item, final Document document, final int offset, PsiFile file) {
    final PsiMethod method = item.getObject();
    if (!AnalyzingJavaSmartCompletionContributor.hasUnboundTypeParams(method)) {
      return;
    }

    PsiDocumentManager.getInstance(file.getProject()).commitAllDocuments();

    PsiExpression expression = PsiTreeUtil.findElementOfClassAtOffset(file, offset - 1, PsiExpression.class, false);
    if (expression == null) return;

    final Project project = file.getProject();
    final ExpectedTypeInfo[] expectedTypes = ExpectedTypesProvider.getInstance(project).getExpectedTypes(expression, true, false);
    if (expectedTypes == null) return;

    for (final ExpectedTypeInfo type : expectedTypes) {
      if (type.isInsertExplicitTypeParams()) {
        final OffsetMap map = new OffsetMap(document);
        final OffsetKey refOffsetKey = OffsetKey.create("refOffset");
        map.addOffset(refOffsetKey, offset - 1);

        final String typeParams = getTypeParamsText(method, type.getType());
        if (typeParams == null) {
          return;
        }
        final String qualifierText = getQualifierText(file, method, offset - 1);

        document.insertString(offset - method.getName().length(), qualifierText + typeParams);
        PsiDocumentManager.getInstance(project).commitDocument(document);

        final PsiReference reference = file.findReferenceAt(map.getOffset(refOffsetKey));
        if (reference instanceof PsiJavaCodeReferenceElement) {
          try {
            CodeInsightUtilBase.forcePsiPostprocessAndRestoreElement(
              JavaCodeStyleManager.getInstance(project).shortenClassReferences((PsiElement)reference));
          }
          catch (IncorrectOperationException e) {
            LOG.error(e);
          }
        }
        return;
      }
    }
    

  }

  private static String getQualifierText(PsiFile file, PsiMethod method, final int refOffset) {
    final PsiReference reference = file.findReferenceAt(refOffset);
    if (reference instanceof PsiJavaCodeReferenceElement && ((PsiJavaCodeReferenceElement)reference).isQualified()) {
      return "";
    }

    final PsiClass containingClass = method.getContainingClass();
    if (containingClass == null) {
      return "";
    }

    if (method.hasModifierProperty(PsiModifier.STATIC)) {
      return containingClass.getQualifiedName() + ".";
    }

    if (containingClass.getManager().areElementsEquivalent(containingClass, PsiTreeUtil.findElementOfClassAtOffset(file, refOffset, PsiClass.class, false))) {
      return "this.";
    }

    return containingClass.getQualifiedName() + ".this.";
  }

  @Nullable
  private static String getTypeParamsText(final PsiMethod method, PsiType expectedType) {
    final PsiSubstitutor substitutor = AnalyzingJavaSmartCompletionContributor.calculateMethodReturnTypeSubstitutor(method, expectedType);
    assert substitutor != null;
    final PsiTypeParameter[] parameters = method.getTypeParameters();
    assert parameters.length > 0;
    final StringBuilder builder = new StringBuilder("<");
    boolean first = true;
    for (final PsiTypeParameter parameter : parameters) {
      if (!first) builder.append(", ");
      first = false;
      final PsiType type = substitutor.substitute(parameter);
      if (type == null || type instanceof PsiWildcardType || type instanceof PsiCapturedWildcardType) return null;

      final String text = type.getCanonicalText();
      if (text.indexOf('?') >= 0) return null;

      builder.append(text);
    }
    return builder.append(">").toString();
  }
}
