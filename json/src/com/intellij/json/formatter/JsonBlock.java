package com.intellij.json.formatter;

import com.intellij.formatting.*;
import com.intellij.json.JsonElementTypes;
import com.intellij.json.JsonLanguage;
import com.intellij.json.JsonParserDefinition;
import com.intellij.json.psi.JsonProperty;
import com.intellij.json.psi.JsonPsiUtil;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.FormatterUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.intellij.json.JsonParserDefinition.JSON_BRACES;
import static com.intellij.json.JsonParserDefinition.JSON_BRACKETS;
import static com.intellij.json.formatter.JsonCodeStyleSettings.PropertyAlignment.ALIGN_ON_COLON;
import static com.intellij.json.formatter.JsonCodeStyleSettings.PropertyAlignment.ALIGN_ON_VALUE;

/**
 * @author Mikhail Golubev
 */
public class JsonBlock implements ASTBlock {
  private static final TokenSet OPEN_BRACES = TokenSet.create(JsonElementTypes.L_BRACKET, JsonElementTypes.L_CURLY);
  private static final TokenSet CLOSE_BRACES = TokenSet.create(JsonElementTypes.R_BRACKET, JsonElementTypes.R_CURLY);
  private static final TokenSet BRACES = TokenSet.orSet(OPEN_BRACES, CLOSE_BRACES);

  private JsonBlock myParent;

  private ASTNode myNode;
  private PsiElement myPsiElement;
  private Alignment myAlignment;
  private Indent myIndent;
  private Wrap myWrap;
  private CodeStyleSettings mySettings;
  private SpacingBuilder mySpacingBuilder;
  // lazy initialized on first call to #getSubBlocks()
  private List<Block> mySubBlocks = null;

  private Alignment myChildAlignment = Alignment.createAlignment();

  private Alignment myPropertyValueAlignment = Alignment.createAlignment(true);

  public JsonBlock(@Nullable JsonBlock parent,
                   @NotNull ASTNode node,
                   @NotNull CodeStyleSettings settings,
                   @Nullable Alignment alignment,
                   @NotNull Indent indent,
                   @Nullable Wrap wrap) {
    myParent = parent;
    myNode = node;
    myPsiElement = node.getPsi();
    myAlignment = alignment;
    myIndent = indent;
    myWrap = wrap;
    mySettings = settings;

    mySpacingBuilder = JsonFormattingBuilderModel.createSpacingBuilder(settings);
  }

  @Override
  public ASTNode getNode() {
    return myNode;
  }

  @NotNull
  @Override
  public TextRange getTextRange() {
    return myNode.getTextRange();
  }

  @NotNull
  @Override
  public List<Block> getSubBlocks() {
    if (mySubBlocks == null) {
      mySubBlocks = ContainerUtil.mapNotNull(myNode.getChildren(null), new Function<ASTNode, Block>() {
        @Override
        public Block fun(ASTNode node) {
          if (isWhitespaceOrEmpty(node)) {
            return null;
          }
          return makeSubBlock(node);
        }
      });
    }
    return mySubBlocks;
  }

  private Block makeSubBlock(@NotNull ASTNode childNode) {
    final IElementType childNodeType = childNode.getElementType();

    Indent indent = Indent.getNoneIndent();
    Alignment alignment = null;
    Wrap wrap = null;

    if (isContainer() && childNodeType != JsonElementTypes.COMMA && !BRACES.contains(childNodeType)) {
      // TODO Add different wrapping options for objects and arrays
      if (!FormatterUtil.isPrecededBy(childNode, OPEN_BRACES)) {
        wrap = Wrap.createWrap(WrapType.ALWAYS, true);
      }
      alignment = myChildAlignment;
      indent = Indent.getNormalIndent();
    }
    // Handle properties alignment
    else if (myNode.getElementType() == JsonElementTypes.PROPERTY) {
      assert myParent.myNode.getElementType() == JsonElementTypes.OBJECT;
      if (childNode.getElementType() == JsonElementTypes.COLON && getCustomSettings().PROPERTY_ALIGNMENT == ALIGN_ON_COLON) {
        alignment = myParent.myPropertyValueAlignment;
      }
      else if (JsonPsiUtil.isPropertyValue(childNode.getPsi()) && getCustomSettings().PROPERTY_ALIGNMENT == ALIGN_ON_VALUE) {
        alignment = myParent.myPropertyValueAlignment;
      }
    }
    return new JsonBlock(this, childNode, mySettings, alignment, indent, wrap);
  }

  @Nullable
  @Override
  public Wrap getWrap() {
    return myWrap;
  }

  @Nullable
  @Override
  public Indent getIndent() {
    return myIndent;
  }

  @Nullable
  @Override
  public Alignment getAlignment() {
    return myAlignment;
  }

  @Nullable
  @Override
  public Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
    final CommonCodeStyleSettings commonSettings = getCommonSettings();
    final JsonCodeStyleSettings customSettings = getCustomSettings();
    final IElementType leftChildType = child1 instanceof JsonBlock ? ((JsonBlock)child1).myNode.getElementType() : null;
    final IElementType rightChildType = child2 instanceof JsonBlock ? ((JsonBlock)child2).myNode.getElementType() : null;
    if (leftChildType != null && rightChildType != null) {
      if (JSON_BRACES.contains(leftChildType) ^ JSON_BRACES.contains(rightChildType)) {
        final int numSpaces = customSettings.SPACE_WITHIN_BRACES ? 1 : 0;
        return Spacing.createDependentLFSpacing(numSpaces, numSpaces, myNode.getTextRange(),
                                                commonSettings.KEEP_LINE_BREAKS,
                                                commonSettings.KEEP_BLANK_LINES_IN_CODE);
      }
      else if (JSON_BRACKETS.contains(leftChildType) ^ JSON_BRACKETS.contains(rightChildType)) {
        final int numSpaces = commonSettings.SPACE_WITHIN_BRACKETS ? 1 : 0;
        return Spacing.createDependentLFSpacing(numSpaces, numSpaces, myNode.getTextRange(),
                                                commonSettings.KEEP_LINE_BREAKS,
                                                commonSettings.KEEP_BLANK_LINES_IN_CODE);
      }
    }
    return mySpacingBuilder.getSpacing(this, child1, child2);
  }

  @NotNull
  @Override
  public ChildAttributes getChildAttributes(int newChildIndex) {
    JsonBlock prevChildBlock = newChildIndex > 0 ? (JsonBlock)mySubBlocks.get(newChildIndex - 1) : null;
    ASTNode prevChildNode = prevChildBlock != null ? prevChildBlock.myNode : null;
    if (myNode.getElementType() == JsonParserDefinition.FILE) {
      return new ChildAttributes(Indent.getNoneIndent(), null);
    }
    if (isContainer() && prevChildNode != null) {
      // correctly indent first element after opening brace
      if (OPEN_BRACES.contains(prevChildNode.getElementType()) || prevChildNode.getElementType() == JsonElementTypes.COMMA) {
        return new ChildAttributes(Indent.getNormalIndent(), myChildAlignment);
      }
    }
    return new ChildAttributes(Indent.getNormalIndent(), null);
  }

  @Override
  public boolean isIncomplete() {
    IElementType nodeType = myNode.getElementType();
    ASTNode lastChildNode = myNode.getLastChildNode();
    if (nodeType == JsonElementTypes.OBJECT) {
      return lastChildNode != null && lastChildNode.getElementType() == JsonElementTypes.R_CURLY;
    }
    else if (nodeType == JsonElementTypes.ARRAY) {
      return lastChildNode != null && lastChildNode.getElementType() == JsonElementTypes.R_BRACKET;
    }
    else if (myPsiElement instanceof JsonProperty) {
      return ((JsonProperty)myPsiElement).getValue() != null;
    }
    return false;
  }

  @Override
  public boolean isLeaf() {
    return myNode.getFirstChildNode() == null;
  }

  private static boolean isWhitespaceOrEmpty(ASTNode node) {
    return node.getElementType() == TokenType.WHITE_SPACE || node.getTextLength() == 0;
  }

  private boolean isContainer() {
    return JsonParserDefinition.JSON_CONTAINERS.contains(myNode.getElementType());
  }

  private JsonCodeStyleSettings getCustomSettings() {
    return mySettings.getCustomSettings(JsonCodeStyleSettings.class);
  }

  private CommonCodeStyleSettings getCommonSettings() {
    return mySettings.getCommonSettings(JsonLanguage.INSTANCE);
  }
}
