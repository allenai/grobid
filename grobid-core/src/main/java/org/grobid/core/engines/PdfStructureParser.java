package org.grobid.core.engines;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.tuple.Pair;
import org.grobid.core.GrobidModels;
import org.grobid.core.data.*;
import org.grobid.core.document.*;
import org.grobid.core.engines.citations.LabeledReferenceResult;
import org.grobid.core.engines.label.SegmentationLabels;
import org.grobid.core.engines.label.TaggingLabel;
import org.grobid.core.engines.label.TaggingLabels;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.layout.LayoutTokenization;
import org.grobid.core.layout.Page;
import org.grobid.core.tokenization.TaggingTokenCluster;
import org.grobid.core.tokenization.TaggingTokenClusteror;
import org.grobid.core.utilities.LayoutTokensUtil;
import org.grobid.core.utilities.TextUtilities;
import org.grobid.core.utilities.matching.EntityMatcherException;
import org.grobid.core.utilities.matching.ReferenceMarkerMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;


/**
 * Uses Grobid's mark-up to produce detailed token-level mark-up of the PDF text
 * in a form appropriate for consumption by the PDF Structure Service
 * https://github.com/allenai/s2-pdf-structure-service
 */
public class PdfStructureParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfStructureParser.class);

    public PdfStructure extractStructure(Engine engine, Document doc) {
        PdfTextElements elements = new PdfTextElements();

        processReferences(engine, doc, elements);
        processEquations(doc, elements);
        processFigures(doc, elements);
        processTables(doc, elements);
        processBodyText(engine, doc, elements);
        processHeader(engine, doc, elements);
//        processSymbols(doc, elements); // This should be done external to Grobid

        PdfTokens tokens = new PdfTokens(doc);
        PdfStructure structure = new PdfStructure(tokens, elements);
        return structure;
    }

    // Identify the tokens in each bibliography entry
    // Also identify the sub-elements of the entry (authors, title, etc.)
    // Give each bib-entry an ID tag for cross-reference with citations
    private void processReferences(Engine engine, Document doc, PdfTextElements elements) {
        TextElement bibliography = new TextElement("<references>");
        elements.add(bibliography);
        List<LabeledReferenceResult> references = engine.getParsers().getReferenceSegmenterParser().extract(doc);
        List<BibDataSet> bibs = doc.getBibDataSets();
        if (bibs != null && references != null) {
            Map<String, BibDataSet> byText = new HashMap<String, BibDataSet>();
            for (BibDataSet bib : bibs) {
                byText.put(bib.getResBib().getReference(), bib);
            }
            for (LabeledReferenceResult ref : references) {
                BibDataSet bib = byText.get(ref.getReferenceText());
                if (bib != null) {
                    String bibItemId = String.valueOf(bib.getResBib().getOrdinal());
                    elements.addElement("<bibItem>", doc, ref.getTokens())
                        .addTag(Tags.ID, bibItemId);
                    bibliography.addTokens(doc, ref.getTokens());
                    // Grobid parses the bib-entry with a standalone model,
                    // which is separate from the top-level document model.
                    // This logic recovers the tokens in the document based on
                    // their relative position within the bib-item
                    if (bib.getResBib() != null && !ref.getTokens().isEmpty()) {
                        List<LayoutToken> tokensFromDocument = ref.getTokens();
                        List<LayoutToken> tokensFromBib = new ArrayList<LayoutToken>();
                        for (Map.Entry<String, List<LayoutToken>> e : bib.getResBib().getLabeledTokens().entrySet()) {
                            tokensFromBib.addAll(e.getValue());
                        }
                        Collections.sort(tokensFromBib, Comparator.comparingInt(t -> t.getOffset()));
                        int[] positionInDoc = new int[tokensFromBib.size()];
                        Map<Integer, Integer> offsetToIndex = new HashMap<Integer, Integer>();
                        int docI = 0, bibI = 0;
                        outerloop:
                        while (bibI < tokensFromBib.size()) {
                            while (!tokensFromDocument.get(docI).getText().equals(tokensFromBib.get(bibI).getText())) {
                                docI++;
                                if (docI >= tokensFromDocument.size()) {
                                    break outerloop;
                                }
                            }
                            positionInDoc[bibI] = docI;
                            offsetToIndex.put(tokensFromBib.get(bibI).getOffset(), bibI);
                            bibI++;
                        }
                        if (bibI < tokensFromBib.size()) {
                            LOGGER.warn("Unable to find tokens in parent document following '" + tokensFromBib.get(bibI) + "' inside bibItem '" + ref.getReferenceText() + "'");
                        } else {
                            for (Map.Entry<String, List<LayoutToken>> e : bib.getResBib().getLabeledTokens().entrySet()) {
                                List<LayoutToken> elementTokens = new ArrayList<LayoutToken>();
                                for (LayoutToken t : e.getValue()) {
                                    int docIndex = positionInDoc[offsetToIndex.get(t.getOffset())];
                                    LayoutToken originalToken = tokensFromDocument.get(docIndex);
                                    elementTokens.add(originalToken);
                                }
                                String elementType = "<bibItem_" + e.getKey().substring(1);
                                elements.addElement(elementType, doc, elementTokens)
                                    .addTag(Tags.ID, bibItemId);
                            }
                        }
                    }
                }
            }
        }
    }

    // Identify the tokens in each equation
    private void processEquations(Document doc, PdfTextElements elements) {
        List<Equation> equations = doc.getEquations();
        if (equations != null) {
            for (Equation eq : equations) {
                elements.addElement(TaggingLabels.EQUATION.getLabel(), doc, eq.getLayoutTokens())
                    .addTag(Tags.ID, eq.getId());
            }
        }
    }

    // Identify the tokens in each figure
    private void processFigures(Document doc, PdfTextElements elements) {
        List<Figure> figures = doc.getFigures();
        if (figures != null) {
            for (Figure fig : figures) {
                elements.addElement(TaggingLabels.FIGURE.getLabel(), doc, fig.getLayoutTokens())
                    .addTag(Tags.ID, fig.getId());
            }
        }
    }

    // Identify the tokens in each table
    private void processTables(Document doc, PdfTextElements elements) {
        List<Table> tables = doc.getTables();
        if (tables != null) {
            for (Table tab : tables) {
                elements.addElement(TaggingLabels.TABLE.getLabel(), doc, tab.getLayoutTokens())
                    .addTag(Tags.ID, tab.getId());
            }
        }
    }

    private void processHeader(Engine engine, Document doc, PdfTextElements elements) {
        try {
            BiblioItem resHeader = new BiblioItem();
            engine.getParsers().getHeaderParser().processingHeaderBlock(0, doc, resHeader);
            for (Map.Entry<String, List<LayoutToken>> e : resHeader.getLabeledTokens().entrySet()) {
                elements.addElement(e.getKey(), doc, e.getValue());
            }

        } catch (Exception ex) {
            LOGGER.warn("Exception parsing paper header", ex);
        }

    }

    // Identify the tokens in the body text:
    // Paragraphs, sections, and references to bib-entries, tables, figures, and equations
    // This code traverses the model output in the same way as TEIFormatter
    private void processBodyText(Engine engine, Document doc, PdfTextElements elements) {
        Pair<String, LayoutTokenization> featSeg = engine.getParsers().getFullTextParser().getBodyTextFeatured(doc, doc.getDocumentPart(SegmentationLabels.BODY));
        if (featSeg != null) {
            String bodytext = featSeg.getLeft();
            LayoutTokenization layoutTokenization = featSeg.getRight();
            List<LayoutToken> tokenizations = layoutTokenization.getTokenization();
            String result = null;
            if ((bodytext != null) && (bodytext.trim().length() > 0)) {
                result = engine.getParsers().getFullTextParser().label(bodytext);
            } else {
                LOGGER.debug("Fulltext model: The input to the CRF processing is empty");
            }

            // Grobid's sequence tagger only tags the beginning of token sequences
            // (as opposed to the beginning/middle/end)
            // These are the types of elements that can appear within a paragraph
            // without signalling the beginning of a new paragraph
            Set<TaggingLabel> canInterruptParagraph = Sets.newHashSet(
                TaggingLabels.CITATION_MARKER,
                TaggingLabels.FIGURE_MARKER,
                TaggingLabels.TABLE_MARKER,
                TaggingLabels.EQUATION_MARKER,
                TaggingLabels.TABLE,
                TaggingLabels.FIGURE
            );
            TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.FULLTEXT, result, tokenizations);
            List<TaggingTokenCluster> clusters = clusteror.cluster();
            TaggingLabel lastClusterLabel = null;
            TextElement currentSection = null;
            TextElement currentParagraph = null;
            for (TaggingTokenCluster cluster : clusters) {
                if (cluster == null) {
                    continue;
                }

                TaggingLabel clusterLabel = cluster.getTaggingLabel();
                List<LayoutToken> clusterTokens = cluster.concatTokens();
                if (clusterLabel.equals(TaggingLabels.ITEM)) {
                    // As in bullet item from a list
                    String clusterContent = LayoutTokensUtil.normalizeText(cluster.concatTokens());
                    addTo(currentParagraph, doc, clusterTokens);
                    addTo(currentSection, doc, clusterTokens);
                } else if (clusterLabel.equals(TaggingLabels.SECTION)) {
                    currentSection = new TextElement(TaggingLabels.SECTION.getLabel());
                    elements.add(currentSection);
                    currentSection.addTokens(doc, clusterTokens);
                } else if (clusterLabel.equals(TaggingLabels.PARAGRAPH)) {
                    boolean isNewParagraph = currentParagraph == null
                        || !canInterruptParagraph.contains(lastClusterLabel);
                    if (isNewParagraph) {
                        currentParagraph = new TextElement(TaggingLabels.PARAGRAPH.getLabel());
                        elements.add(currentParagraph);
                    }
                    currentParagraph.addTokens(doc, clusterTokens);
                    addTo(currentSection, doc, clusterTokens);
                } else if (clusterLabel.equals(TaggingLabels.CITATION_MARKER)) {
                    // A citation. Find the matching bib-entry
                    boolean foundMatch = false;
                    try {
                        List<ReferenceMarkerMatcher.MatchResult> matchResults = doc.getReferenceMarkerMatcher().match(cluster.concatTokens());
                        if (matchResults != null) {
                            for (ReferenceMarkerMatcher.MatchResult matchResult : matchResults) {
                                TextElement citRef = elements.addElement(clusterLabel.getLabel(), doc, matchResult.getTokens());
                                foundMatch = true;
                                if (matchResult.getBibDataSet() != null) {
                                    citRef.addTag(Tags.REF, String.valueOf(matchResult.getBibDataSet().getResBib().getOrdinal()));
                                }
                            }
                        }
                    } catch (EntityMatcherException e) {
                        LOGGER.warn("Error attempting to match citation reference", e);
                    }
                    if (!foundMatch) {
                        elements.addElement(clusterLabel.getLabel(), doc, clusterTokens);
                    }

                } else if (clusterLabel.equals(TaggingLabels.FIGURE_MARKER)) {
                    // A reference to a figure. Find the matching figure
                    String refId = null;
                    String text = LayoutTokensUtil.toText(cluster.concatTokens()).toLowerCase();
                    if (doc.getFigures() != null) {
                        for (Figure fig : doc.getFigures()) {
                            if (fig.getLabel() != null && fig.getLabel().length() > 0) {
                                String label = TextUtilities.cleanField(fig.getLabel(), false).toLowerCase();
                                if (label.length() > 0 && text.contains(label)) {
                                    refId = fig.getId();
                                    break;
                                }
                            }
                        }
                    }
                    TextElement el = elements.addElement(clusterLabel.getLabel(), doc, clusterTokens);
                    if (refId != null) {
                        el.addTag(Tags.REF, refId);
                    }
                    addTo(currentParagraph, doc, clusterTokens);
                    addTo(currentSection, doc, clusterTokens);
                } else if (clusterLabel.equals(TaggingLabels.TABLE_MARKER)) {
                    // A reference to a table. Find the matching table
                    String refId = null;
                    String text = LayoutTokensUtil.toText(cluster.concatTokens()).toLowerCase();
                    if (doc.getTables() != null) {
                        for (Table tab : doc.getTables()) {
                            if (tab.getLabel() != null && tab.getLabel().length() > 0) {
                                String label = TextUtilities.cleanField(tab.getLabel(), false).toLowerCase();
                                if (label.length() > 0 && text.contains(label)) {
                                    refId = tab.getId();
                                    break;
                                }
                            }
                        }
                    }
                    TextElement el = elements.addElement(clusterLabel.getLabel(), doc, clusterTokens);
                    if (refId != null) {
                        el.addTag(Tags.REF, refId);
                    }
                    addTo(currentParagraph, doc, clusterTokens);
                    addTo(currentSection, doc, clusterTokens);
                } else if (clusterLabel.equals(TaggingLabels.EQUATION_MARKER)) {
                    // A reference to an equation. Find the matching equation.
                    String refId = null;
                    String text = LayoutTokensUtil.toText(cluster.concatTokens()).toLowerCase();
                    if (doc.getEquations() != null) {
                        for (Equation eq : doc.getEquations()) {
                            if ((eq.getLabel() != null) && (eq.getLabel().length() > 0)) {
                                String label = TextUtilities.cleanField(eq.getLabel(), false).toLowerCase();
                                if (label.length() > 0 && text.contains(label)) {
                                    refId = eq.getId();
                                    break;
                                }
                            }
                        }
                    }
                    TextElement el = elements.addElement(clusterLabel.getLabel(), doc, clusterTokens);
                    if (refId != null) {
                        el.addTag(Tags.REF, refId);
                    }
                    addTo(currentParagraph, doc, clusterTokens);
                    addTo(currentSection, doc, clusterTokens);
                } else {
                    LOGGER.debug("Skipping label " + clusterLabel);
                }
                lastClusterLabel = cluster.getTaggingLabel();
            }

        } else {
            LOGGER.debug("Fulltext model: The featured body is empty");
        }
    }

    // Identify the tokens that are mathematical symbols, via some heuristics
    // Assume that symbols are rendered using specialized fonts
    // Any font that is used predominantly for single-character non-punctuation tokens
    // is assumed to be a symbol font,
    // and any text rendered using that font is identified as a symbol
    private void processSymbols(Document doc, PdfTextElements elements) {
        Pattern ignore = Pattern.compile("[\\s\\p{Punct}]*");
        // Get a histogram of word length for each token style used in the document
        // Value is number of tokens of length, 1, 2, 3+
        Map<TextStyle, int[]> hist = new HashMap<TextStyle, int[]>();
        for (LayoutToken t : doc.getTokenizations()) {
            if (!ignore.matcher(t.getText()).matches()) {
                int[] count = hist.computeIfAbsent(TextStyle.from(t), k -> new int[3]);
                count[Math.max(0, Math.min(t.getText().length() - 1, 2))]++;
            }
        }
        // Identify fonts used predominantly for single-character tokens
        Set<TextStyle> symbolFonts = new HashSet<TextStyle>();
        for (Map.Entry<TextStyle, int[]> kvp : hist.entrySet()) {
            int[] counts = kvp.getValue();
            if (counts[0] > Math.max(counts[1], counts[2])) {
                symbolFonts.add(kvp.getKey());
            }
        }

        // Find the symbols. Give each symbol an ID of font+text
        for (LayoutToken t : doc.getTokenizations())
            if (!ignore.matcher(t.getText()).matches() && symbolFonts.contains(TextStyle.from(t))) {
                // Normalize the font name
                String font = t.getFont();
                int index = font.indexOf('+');
                if (index > 0) {
                    font = font.substring(index + 1);
                }
                index = font.indexOf('-');
                if (index > 0) {
                    font = font.substring(0, index);
                }
                elements.addElement("<symbol>", doc, Collections.singletonList(t))
                    .addTag(Tags.ID, font + ":" + t.getText());
            }
    }

    private void addTo(TextElement element, Document doc, List<LayoutToken> tokens) {
        if (element != null) {
            element.addTokens(doc, tokens);
        }
    }

    /**
     * The set of tokens extracted from the PDF
     * and the text elements identified within it
     */
    public static class PdfStructure {
        private PdfTokens tokens;
        private PdfTextElements elements;

        public PdfStructure(PdfTokens tokens, PdfTextElements elements) {
            this.tokens = tokens;
            this.elements = elements;
            elements.dehyphenize(tokens);
            elements.useNonwhitespaceIndices(tokens);
        }

        public PdfTextElements getElements() {
            return elements;
        }

        public PdfTokens getTokens() {
            return tokens;
        }

        public List<String> textOf(TextElement el) {
            if (!el.usesNonwhitespaceIndices) {
                throw new IllegalStateException("TextElement " + el.getType() + " has not been converted to use non-whitespace tokens");
            }
            ArrayList<String> tokens = new ArrayList<String>();
            for (Span s : el.spans) {
                if (s.dehyphenizedText != null) {
                    tokens.addAll(s.dehyphenizedText);
                } else {
                    for (int i = s.left; i < s.right; ++i) {
                        tokens.add(this.tokens.nonwhitespaceTokens.get(i).getText());
                    }
                }
            }
            return tokens;
        }
    }

    /**
     * Set of text elements, organized by type
     */
    public static class PdfTextElements {
        private Map<String, ArrayList<TextElement>> elementTypes = new HashMap<>();

        public PdfTextElements() {
        }

        public void add(TextElement element) {
            if (element != null) {
                ArrayList<TextElement> elements = elementTypes.computeIfAbsent(element.getType(), k -> new ArrayList<TextElement>());
                elements.add(element);
            }
        }

        public TextElement addElement(String elementType, Document doc, List<LayoutToken> tokens) {
            ArrayList<TextElement> elements = elementTypes.computeIfAbsent(elementType, k -> new ArrayList<TextElement>());
            TextElement el = new TextElement(elementType);
            el.addTokens(doc, tokens);
            elements.add(el);
            return el;
        }

        public Map<String, ArrayList<TextElement>> getElementTypes() {
            return elementTypes;
        }

        // Mutate each text element so that the spans refer to token offsets
        // in the list of non-whitespace tokens,
        // instead of the list of whitespace-plus-non-whitespace tokens
        public void useNonwhitespaceIndices(PdfTokens tokens) {
            for (List<TextElement> els : elementTypes.values()) {
                for (TextElement el : els) {
                    el.useNonwhitespaceIndices(tokens);
                }
            }
        }

        private static Set<String> shouldDehyphenize = Sets.newHashSet(
            TaggingLabels.PARAGRAPH.getLabel(),
            TaggingLabels.ABSTRACT_LABEL,
            "<bibItem_" + TaggingLabels.CITATION_TITLE.getLabel().substring(1));

        // Mutate the text elements to specify dehyphenized text, if appropriate
        public void dehyphenize(PdfTokens tokens) {
            for (List<TextElement> els : elementTypes.values()) {
                for (TextElement el : els) {
                    if (shouldDehyphenize.contains(el.getType())) {
                        el.dehyphenize(tokens);
                    }
                }

            }
        }

    }

    /**
     * Two differences between Grobid's raw tokenization:
     * 1. Grobid copies the style information into each token.
     * Here, the styles are referenced by name (as in the pdf-alto XML file)
     * 2. Grobid inserts whitespace tokens (spaces and line breaks).
     * Here, only non-whitespace tokens are retained.
     */
    public static class PdfTokens {
        private int[] nonwhitespaceIndex;
        private List<LayoutToken> allTokens;
        private List<LayoutToken> nonwhitespaceTokens;

        private Map<String, TextStyle> styles;
        private List<PageTokens> pages;

        public PdfTokens(Document doc) {
            allTokens = doc.getTokenizations();
            nonwhitespaceTokens = new ArrayList<LayoutToken>(allTokens.size() / 2);

            this.nonwhitespaceIndex = new int[allTokens.size()];
            int originalIndex = 0;
            int nonwhitespaceIndex = -1;
            for (LayoutToken token : allTokens) {
                if (!isWhitespace(token)) {
                    nonwhitespaceTokens.add(token);
                    nonwhitespaceIndex++;
                }
                this.nonwhitespaceIndex[originalIndex] = nonwhitespaceIndex;
                originalIndex++;
            }

            HashMap<TextStyle, String> styleNames = new HashMap<TextStyle, String>();
            int styleCount = 0;
            pages = new ArrayList<PageTokens>();
            for (LayoutToken token : nonwhitespaceTokens) {
                TextStyle style = TextStyle.from(token);
                String styleName = styleNames.get(style);
                if (styleName == null) {
                    styleName = "style" + styleCount;
                    styleNames.put(style, styleName);
                    styleCount++;
                }
                int pageNumber = doc.getBlocks().get(token.getBlockPtr()).getPageNumber();
                PageTokens page = null;
                for (PageTokens p : pages) {
                    if (p.getPage().pageNumber == pageNumber) {
                        page = p;
                        break;
                    }
                }
                if (page == null) {
                    for (Page p : doc.getPages()) {
                        if (p.getNumber() == pageNumber) {
                            page = new PageTokens(new PdfPage(p.getNumber(), p.getWidth(), p.getHeight()));
                            pages.add(page);
                            break;
                        }
                    }
                }
                page.add(new Token(
                    token.getText(),
                    token.getX(),
                    token.getY(),
                    token.getWidth(),
                    token.getHeight(),
                    styleName
                ));
            }
            styles = new HashMap<String, TextStyle>();
            for (Map.Entry<TextStyle, String> kvp : styleNames.entrySet()) {
                styles.put(kvp.getValue(), kvp.getKey());
            }
        }

        // Identify whitepace token inserted by Grobid's XML parser
        public static boolean isWhitespace(LayoutToken token) {
            if (token.width == 0 && token.height == 0 && token.x == -1. && token.y == -1.) {
                if (!"".equals(token.getText().trim())) {
                    throw new IllegalArgumentException("Unexpected zero-size non-whitespace token '" + token.getText() + "'at index " + token.getOffset());
                }
                return true;
            } else {
                return false;
            }
        }

        // For the given input span, in terms of whitespace-included tokens,
        // Find the corresponding span in terms of non-whitespace tokens
        public Span nonwhitespaceIndices(Span span) {
            int left = nonwhitespaceIndex[span.left];
            int right = nonwhitespaceIndex[span.right];
            if (PdfTokens.isWhitespace(allTokens.get(span.right))) {
                right += 1;
            }
            Span s = new Span(left, right);
            s.setDehyphenizedText(span.getDehyphenizedText());
            return s;
        }


        public Map<String, TextStyle> getStyles() {
            return styles;
        }

        public List<PageTokens> getPages() {
            return pages;
        }
    }

    public static class Token {
        private String text;
        private double x;
        private double y;
        private double width;
        private double height;
        private String styleName;

        public Token(String text, double x, double y, double width, double height, String styleName) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.styleName = styleName;
        }

        public String getText() {
            return text;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getWidth() {
            return width;
        }

        public double getHeight() {
            return height;
        }

        public String getStyleName() {
            return styleName;
        }
    }

    /**
     * The page number is the printed page number, not the index in the list of pages
     * Width and height units are unspecified (PDFs use "points")
     * but will be the same unit as used by the tokens
     */
    public static class PdfPage {
        private int pageNumber;
        private double width;
        private double height;

        public PdfPage(int pageNumber, double width, double height) {
            this.pageNumber = pageNumber;
            this.width = width;
            this.height = height;
        }

        public int getPageNumber() {
            return pageNumber;
        }

        public double getWidth() {
            return width;
        }

        public double getHeight() {
            return height;
        }
    }

    /**
     * The set of tokens that appear on a single page
     */
    public static class PageTokens {
        private PdfPage page;
        private List<Token> tokens = new ArrayList<Token>();

        public PageTokens(PdfPage page) {
            this.page = page;
        }

        public PdfPage getPage() {
            return page;
        }

        public List<Token> getTokens() {
            return tokens;
        }

        public void add(Token token) {
            tokens.add(token);
        }
    }

    public static class TextStyle {
        private double fontSize = 0.0;
        private String fontName = null;
        private String fontColor = null;

        private boolean subscript = false;
        private boolean superscript = false;

        public TextStyle(double fontSize, String fontName, String fontColor, boolean subscript, boolean superscript) {
            this.fontSize = fontSize;
            this.fontName = fontName;
            this.fontColor = fontColor;
            this.subscript = subscript;
            this.superscript = superscript;
        }

        public static TextStyle from(LayoutToken token) {
            return new TextStyle(token.fontSize, token.getFont(), token.getColorFont(), token.isSubscript(), token.isSuperscript());
        }

        public double getFontSize() {
            return fontSize;
        }

        public String getFontName() {
            return fontName;
        }

        public String getFontColor() {
            return fontColor;
        }

        public boolean isSubscript() {
            return subscript;
        }

        public boolean isSuperscript() {
            return superscript;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TextStyle textStyle = (TextStyle) o;
            return Double.compare(textStyle.fontSize, fontSize) == 0 &&
                subscript == textStyle.subscript &&
                superscript == textStyle.superscript &&
                Objects.equals(fontName, textStyle.fontName) &&
                Objects.equals(fontColor, textStyle.fontColor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fontSize, fontName, fontColor, subscript, superscript);
        }
    }

    /**
     * A span identifying a contiguous sequence of tokens.
     * If dehyphenizedText is null, then all of the underlying tokens are included.
     * If non-null, then some underlying hyphen tokens should be skipped
     * so as to produce the dehyphenated text.
     */
    public static class Span {
        public final int left;
        public final int right;
        private List<String> dehyphenizedText;

        public Span(int left, int right) {
            this.left = left;
            this.right = right;
        }

        public int getLeft() {
            return left;
        }

        public int getRight() {
            return right;
        }

        public List<String> getDehyphenizedText() {
            return dehyphenizedText;
        }

        public void setDehyphenizedText(List<String> dehyphenizedText) {
            this.dehyphenizedText = dehyphenizedText;
        }
    }

    /**
     * A sequence of tokens (not necessarily contiguous in the PDF)
     * For example, a sentence, paragraph, section, figure caption, bibliography entry
     */
    public static class TextElement {
        private String type;
        private List<Span> spans;
        private Map<String, String> tags = new HashMap<>();
        private boolean usesNonwhitespaceIndices = false;

        public TextElement(String type) {
            this.type = type;
            this.spans = new ArrayList<Span>();
        }

        public TextElement addTag(String key, String value) {
            tags.put(key, value);
            return this;
        }

        public void addTokens(Document doc, List<LayoutToken> tokens) {
            Iterable<Span> spans = Iterables.transform(tokens,
                new Function<LayoutToken, Span>() {
                    @Override
                    public Span apply(LayoutToken token) {
                        int index = FullTextParser.getDocIndexToken(doc, token);
                        return new Span(index, index + 1);
                    }
                });
            List<Span> spanList = Lists.<Span>newArrayList();
            for (Span span : spans) {
                if (spanList.isEmpty()) {
                    spanList.add(span);
                } else {
                    int lastIndex = spanList.size() - 1;
                    Span lastSpan = spanList.get(lastIndex);
                    if (lastSpan.right >= span.left) {
                        spanList.set(lastIndex, new Span(lastSpan.left, Math.max(span.right, lastSpan.right)));
                    } else {
                        spanList.add(span);
                    }
                }

            }
            this.spans.addAll(spanList);
        }

        public String getType() {
            return type;
        }

        public List<Span> getSpans() {
            return spans;
        }

        public Map<String, String> getTags() {
            return tags;
        }

        // Replace spans relative to whitespace-included tokens
        // with spans relative to non-whitespace tokens
        public void useNonwhitespaceIndices(PdfTokens tokens) {
            if (usesNonwhitespaceIndices) {
                throw new IllegalStateException("Attempt to call useNonwhitespaceIndices twice");
            }
            usesNonwhitespaceIndices = true;
            for (int i = 0; i < spans.size(); ++i) {
                spans.set(i, tokens.nonwhitespaceIndices(spans.get(i)));
            }
        }

        public void dehyphenize(PdfTokens tokens) {
            if (usesNonwhitespaceIndices) {
                throw new IllegalStateException("Must dehyphenize before switching to non-whitespace indices");
            }
            spanLoop:
            for (Span span : spans) {
                List<LayoutToken> originalTokens = tokens.allTokens.subList(span.left, span.right);
                List<LayoutToken> dehyphenizedTokens = LayoutTokensUtil.dehyphenize(originalTokens);
                if (!dehyphenizedTokens.equals(originalTokens)) {
                    List<String> text = new ArrayList<String>();
                    int oi = 0, di = 0;
                    while (oi < originalTokens.size() && di < dehyphenizedTokens.size()) {
                        LayoutToken ot = originalTokens.get(oi);
                        boolean oIsWhitespace = PdfTokens.isWhitespace(ot);
                        LayoutToken dt = dehyphenizedTokens.get(di);
                        boolean dIsWhitespace = PdfTokens.isWhitespace(dt);
                        if (!oIsWhitespace && !dIsWhitespace) {
                            if (ot.equals(dt)) {
                                text.add(ot.getText());
                            } else if ("-".equals(ot.getText())) {
                                while (oi < originalTokens.size()) {
                                    oi++;
                                    if (originalTokens.get(oi).equals(dt)) {
                                        text.set(text.size() - 1, text.get(text.size() - 1) + dt.getText());
                                        break;
                                    }
                                }
                                if (oi == originalTokens.size()) {
                                    LOGGER.warn("Failed to align dehypenized text \"" + LayoutTokensUtil.toText(dehyphenizedTokens)
                                        + "\" with original text \"" + LayoutTokensUtil.toText(originalTokens) + "\"");
                                    break spanLoop;
                                }
                            }
                            oi++;
                            di++;
                        } else {
                            if (oIsWhitespace) {
                                oi++;
                            }
                            if (dIsWhitespace) {
                                di++;
                            }
                        }
                    }
                    span.setDehyphenizedText(text);
                }
            }
        }
    }

    public static class Tags {
        public final static String ID = "id";
        public final static String REF = "ref";
    }
}
