/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.template.zencoding;

import com.intellij.application.options.editor.WebEditorOptions;
import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.template.*;
import com.intellij.codeInsight.template.impl.TemplateImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.codeInsight.template.zencoding.filters.ZenCodingFilter;
import com.intellij.codeInsight.template.zencoding.generators.XmlZenCodingGenerator;
import com.intellij.codeInsight.template.zencoding.generators.ZenCodingGenerator;
import com.intellij.codeInsight.template.zencoding.nodes.*;
import com.intellij.codeInsight.template.zencoding.tokens.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.undo.UndoConstants;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ArrayUtil;
import com.intellij.util.LocalTimeCounter;
import com.intellij.xml.XmlBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;


/**
 * @author Eugene.Kudelevsky
 */
public class ZenCodingTemplate implements CustomLiveTemplate {
  public static final char MARKER = '\0';
  private static final String DELIMS = ">+*|()[]{}.#,='\" \0";
  public static final String ATTRS = "ATTRS";

  private static void addMissingAttributes(XmlTag tag, List<Pair<String, String>> value) {
    List<Pair<String, String>> attr2value = new ArrayList<Pair<String, String>>(value);
    for (Iterator<Pair<String, String>> iterator = attr2value.iterator(); iterator.hasNext(); ) {
      Pair<String, String> pair = iterator.next();
      if (tag.getAttribute(pair.first) != null) {
        iterator.remove();
      }
    }
    addAttributesBefore(tag, attr2value);
  }

  private static void addAttributesBefore(XmlTag tag, List<Pair<String, String>> attr2value) {
    XmlAttribute firstAttribute = ArrayUtil.getFirstElement(tag.getAttributes());
    XmlElementFactory factory = XmlElementFactory.getInstance(tag.getProject());
    for (Pair<String, String> pair : attr2value) {
      XmlAttribute xmlAttribute = factory.createXmlAttribute(pair.first, "");
      if (firstAttribute != null) {
        tag.addBefore(xmlAttribute, firstAttribute);
      }
      else {
        tag.add(xmlAttribute);
      }
    }
  }

  @Nullable
  private static ZenCodingGenerator findApplicableDefaultGenerator(@NotNull PsiElement context, boolean wrapping) {
    for (ZenCodingGenerator generator : ZenCodingGenerator.getInstances()) {
      if (generator.isMyContext(context, wrapping) && generator.isAppliedByDefault(context)) {
        return generator;
      }
    }
    return null;
  }

  @NotNull
  private static XmlFile parseXmlFileInTemplate(String templateString, CustomTemplateCallback callback, boolean createPhysicalFile) {
    XmlFile xmlFile = (XmlFile)PsiFileFactory.getInstance(callback.getProject())
      .createFileFromText("dummy.xml", StdFileTypes.XML, templateString, LocalTimeCounter.currentTime(), createPhysicalFile);
    VirtualFile vFile = xmlFile.getVirtualFile();
    if (vFile != null) {
      vFile.putUserData(UndoConstants.DONT_RECORD_UNDO, Boolean.TRUE);
    }
    return xmlFile;
  }

  @Nullable
  private static ZenCodingNode parse(@NotNull String text, @NotNull CustomTemplateCallback callback, ZenCodingGenerator generator) {
    List<ZenCodingToken> tokens = lex(text);
    if (tokens == null) {
      return null;
    }
    if (generator != null && !validate(tokens, generator)) {
      return null;
    }
    EmmetParser parser = new EmmetParser(tokens, callback, generator);
    ZenCodingNode node = parser.parse();
    if (parser.getIndex() != tokens.size() || node instanceof TextNode) {
      return null;
    }
    return node;
  }

  private static boolean validate(@NotNull List<ZenCodingToken> tokens, @NotNull ZenCodingGenerator generator) {
    for (ZenCodingToken token : tokens) {
      if (token instanceof TextToken && !(generator instanceof XmlZenCodingGenerator)) {
        return false;
      }
    }
    return true;
  }

  @Nullable
  private static List<ZenCodingToken> lex(@NotNull String text) {
    text += MARKER;
    final List<ZenCodingToken> result = new ArrayList<ZenCodingToken>();

    boolean inQuotes = false;
    boolean inApostrophes = false;
    int bracesStack = 0;

    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);

      if (inQuotes) {
        builder.append(c);
        if (c == '"') {
          inQuotes = false;
          result.add(new StringLiteralToken(builder.toString()));
          builder = new StringBuilder();
        }
        continue;
      }

      if (inApostrophes) {
        builder.append(c);
        if (c == '\'') {
          inApostrophes = false;
          result.add(new StringLiteralToken(builder.toString()));
          builder = new StringBuilder();
        }
        continue;
      }

      if (bracesStack > 0) {
        builder.append(c);
        if (c == '}') {
          bracesStack--;
          if (bracesStack == 0) {
            result.add(new TextToken(builder.toString()));
            builder = new StringBuilder();
          }
        }
        else if (c == '{') {
          bracesStack++;
        }
        continue;
      }

      if (DELIMS.indexOf(c) < 0) {
        builder.append(c);
      }
      else {
        // handle special case: ul+ template
        if (c == '+' && (i == text.length() - 2 || text.charAt(i + 1) == ')')) {
          builder.append(c);
          continue;
        }

        if (builder.length() > 0) {
          final String tokenText = builder.toString();
          final int n = ZenCodingUtil.parseNonNegativeInt(tokenText);
          if (n >= 0) {
            result.add(new NumberToken(n));
          }
          else {
            result.add(new IdentifierToken(tokenText));
          }
          builder = new StringBuilder();
        }
        if (c == '"') {
          inQuotes = true;
          builder.append(c);
        }
        else if (c == '\'') {
          inApostrophes = true;
          builder.append(c);
        }
        else if (c == '{') {
          bracesStack = 1;
          builder.append(c);
        }
        else if (c == '(') {
          result.add(ZenCodingTokens.OPENING_R_BRACKET);
        }
        else if (c == ')') {
          result.add(ZenCodingTokens.CLOSING_R_BRACKET);
        }
        else if (c == '[') {
          result.add(ZenCodingTokens.OPENING_SQ_BRACKET);
        }
        else if (c == ']') {
          result.add(ZenCodingTokens.CLOSING_SQ_BRACKET);
        }
        else if (c == '=') {
          result.add(ZenCodingTokens.EQ);
        }
        else if (c == '.') {
          result.add(ZenCodingTokens.DOT);
        }
        else if (c == '#') {
          result.add(ZenCodingTokens.SHARP);
        }
        else if (c == ',') {
          result.add(ZenCodingTokens.COMMA);
        }
        else if (c == ' ') {
          result.add(ZenCodingTokens.SPACE);
        }
        else if (c == '|') {
          result.add(ZenCodingTokens.PIPE);
        }
        else if (c != MARKER) {
          result.add(new OperationToken(c));
        }
      }
    }
    if (bracesStack != 0 || inQuotes || inApostrophes) {
      return null;
    }
    return result;
  }


  public static boolean checkTemplateKey(@NotNull String key, CustomTemplateCallback callback, ZenCodingGenerator generator) {
    return parse(key, callback, generator) != null;
  }

  public void expand(String key, @NotNull CustomTemplateCallback callback) {
    ZenCodingGenerator defaultGenerator = findApplicableDefaultGenerator(callback.getContext(), false);
    assert defaultGenerator != null;
    expand(key, callback, null, defaultGenerator);
  }

  @Nullable
  private static ZenCodingGenerator findApplicableGenerator(ZenCodingNode node, PsiElement context, boolean wrapping) {
    ZenCodingGenerator defaultGenerator = null;
    List<ZenCodingGenerator> generators = ZenCodingGenerator.getInstances();
    for (ZenCodingGenerator generator : generators) {
      if (defaultGenerator == null && generator.isMyContext(context, wrapping) && generator.isAppliedByDefault(context)) {
        defaultGenerator = generator;
      }
    }
    while (node instanceof FilterNode) {
      FilterNode filterNode = (FilterNode)node;
      String suffix = filterNode.getFilter();
      for (ZenCodingGenerator generator : generators) {
        if (generator.isMyContext(context, wrapping)) {
          if (suffix != null && suffix.equals(generator.getSuffix())) {
            return generator;
          }
        }
      }
      node = filterNode.getNode();
    }
    return defaultGenerator;
  }

  private static List<ZenCodingFilter> getFilters(ZenCodingNode node, PsiElement context) {
    List<ZenCodingFilter> result = new ArrayList<ZenCodingFilter>();

    while (node instanceof FilterNode) {
      FilterNode filterNode = (FilterNode)node;
      String filterSuffix = filterNode.getFilter();
      boolean filterFound = false;
      for (ZenCodingFilter filter : ZenCodingFilter.getInstances()) {
        if (filter.isMyContext(context) && filter.getSuffix().equals(filterSuffix)) {
          filterFound = true;
          result.add(filter);
        }
      }
      assert filterFound;
      node = filterNode.getNode();
    }

    for (ZenCodingFilter filter : ZenCodingFilter.getInstances()) {
      if (filter.isMyContext(context) && filter.isAppliedByDefault(context)) {
        result.add(filter);
      }
    }

    Collections.reverse(result);
    return result;
  }


  private static void expand(String key,
                             @NotNull CustomTemplateCallback callback,
                             String surroundedText,
                             @NotNull ZenCodingGenerator defaultGenerator) {
    ZenCodingNode node = parse(key, callback, defaultGenerator);
    assert node != null;
    if (surroundedText == null) {
      if (node instanceof TemplateNode) {
        if (key.equals(((TemplateNode)node).getTemplateToken().getKey()) &&
            callback.findApplicableTemplates(key).size() > 1) {
          callback.startTemplate();
          return;
        }
      }
      callback.deleteTemplateKey(key);
    }

    PsiElement context = callback.getContext();
    ZenCodingGenerator generator = findApplicableGenerator(node, context, false);
    List<ZenCodingFilter> filters = getFilters(node, context);

    expand(node, generator, filters, surroundedText, callback);
  }

  private static void expand(ZenCodingNode node,
                             ZenCodingGenerator generator,
                             List<ZenCodingFilter> filters,
                             String surroundedText,
                             CustomTemplateCallback callback) {
    if (surroundedText != null) {
      surroundedText = surroundedText.trim();
    }
    List<GenerationNode> genNodes = node.expand(-1, surroundedText, callback, true);
    LiveTemplateBuilder builder = new LiveTemplateBuilder();
    int end = -1;
    for (int i = 0, genNodesSize = genNodes.size(); i < genNodesSize; i++) {
      GenerationNode genNode = genNodes.get(i);
      TemplateImpl template = genNode.generate(callback, generator, filters, true);
      int e = builder.insertTemplate(builder.length(), template, null);
      if (end == -1 && end < builder.length()) {
        end = e;
      }
    }

    callback.startTemplate(builder.buildTemplate(), null, new TemplateEditingAdapter() {
      private TextRange myEndVarRange;
      private Editor myEditor;

      @Override
      public void beforeTemplateFinished(TemplateState state, Template template) {
        int variableNumber = state.getCurrentVariableNumber();
        if (variableNumber >= 0 && template instanceof TemplateImpl) {
          TemplateImpl t = (TemplateImpl)template;
          while (variableNumber < t.getVariableCount()) {
            String varName = t.getVariableNameAt(variableNumber);
            if (LiveTemplateBuilder.isEndVariable(varName)) {
              myEndVarRange = state.getVariableRange(varName);
              myEditor = state.getEditor();
              break;
            }
            variableNumber++;
          }
        }
      }

      @Override
      public void templateFinished(Template template, boolean brokenOff) {
        if (brokenOff && myEndVarRange != null && myEditor != null) {
          int offset = myEndVarRange.getStartOffset();
          if (offset >= 0 && offset != myEditor.getCaretModel().getOffset()) {
            myEditor.getCaretModel().moveToOffset(offset);
            myEditor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
          }
        }
      }
    });
  }

  public void wrap(final String selection,
                   @NotNull final CustomTemplateCallback callback
  ) {
    InputValidatorEx validator = new InputValidatorEx() {
      public String getErrorText(String inputString) {
        if (!checkTemplateKey(inputString, callback)) {
          return XmlBundle.message("zen.coding.incorrect.abbreviation.error");
        }
        return null;
      }

      public boolean checkInput(String inputString) {
        return getErrorText(inputString) == null;
      }

      public boolean canClose(String inputString) {
        return checkInput(inputString);
      }
    };
    final String abbreviation = Messages
      .showInputDialog(callback.getProject(), XmlBundle.message("zen.coding.enter.abbreviation.dialog.label"),
                       XmlBundle.message("zen.coding.title"), Messages.getQuestionIcon(), "", validator);
    if (abbreviation != null) {
      doWrap(selection, abbreviation, callback);
    }
  }

  public static boolean checkTemplateKey(String inputString, CustomTemplateCallback callback) {
    ZenCodingGenerator generator = findApplicableDefaultGenerator(callback.getContext(), true);
    assert generator != null;
    return checkTemplateKey(inputString, callback, generator);
  }

  public boolean isApplicable(PsiFile file, int offset, boolean wrapping) {
    WebEditorOptions webEditorOptions = WebEditorOptions.getInstance();
    if (!webEditorOptions.isZenCodingEnabled()) {
      return false;
    }
    if (file == null) {
      return false;
    }
    PsiDocumentManager.getInstance(file.getProject()).commitAllDocuments();
    PsiElement element = CustomTemplateCallback.getContext(file, offset);
    return findApplicableDefaultGenerator(element, wrapping) != null;
  }

  protected static void doWrap(final String selection,
                               final String abbreviation,
                               final CustomTemplateCallback callback) {
    final ZenCodingGenerator defaultGenerator = findApplicableDefaultGenerator(callback.getContext(), true);
    assert defaultGenerator != null;
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        CommandProcessor.getInstance().executeCommand(callback.getProject(), new Runnable() {
          public void run() {
            callback.fixInitialState(true);
            ZenCodingNode node = parse(abbreviation, callback, defaultGenerator);
            assert node != null;
            PsiElement context = callback.getContext();
            ZenCodingGenerator generator = findApplicableGenerator(node, context, true);
            List<ZenCodingFilter> filters = getFilters(node, context);

            EditorModificationUtil.deleteSelectedText(callback.getEditor());
            PsiDocumentManager.getInstance(callback.getProject()).commitAllDocuments();

            expand(node, generator, filters, selection, callback);
          }
        }, CodeInsightBundle.message("insert.code.template.command"), null);
      }
    });
  }

  @NotNull
  public String getTitle() {
    return XmlBundle.message("zen.coding.title");
  }

  public char getShortcut() {
    return (char)WebEditorOptions.getInstance().getZenCodingExpandShortcut();
  }

  protected static boolean containsAttrsVar(TemplateImpl template) {
    for (int i = 0; i < template.getVariableCount(); i++) {
      String varName = template.getVariableNameAt(i);
      if (ATTRS.equals(varName)) {
        return true;
      }
    }
    return false;
  }

  public String computeTemplateKey(@NotNull CustomTemplateCallback callback) {
    ZenCodingGenerator generator = findApplicableDefaultGenerator(callback.getContext(), false);
    if (generator == null) return null;
    return generator.computeTemplateKey(callback);
  }

  public boolean supportsWrapping() {
    return true;
  }

  public static boolean doSetTemplate(final TemplateToken token, TemplateImpl template, CustomTemplateCallback callback) {
    token.setTemplate(template);
    final XmlFile xmlFile = parseXmlFileInTemplate(template.getString(), callback, true);
    token.setFile(xmlFile);
    XmlDocument document = xmlFile.getDocument();
    final XmlTag tag = document != null ? document.getRootTag() : null;
    if (token.getAttribute2Value().size() > 0 && tag == null) {
      return false;
    }
    if (tag != null) {
      if (!containsAttrsVar(template) && token.getAttribute2Value().size() > 0) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          public void run() {
            addMissingAttributes(tag, token.getAttribute2Value());
          }
        });
      }
    }
    return true;
  }
}
