package org.grobid.core.engines;

import com.google.common.collect.Lists;
import nu.xom.Builder;
import nu.xom.Nodes;
import org.grobid.core.document.Document;
import org.grobid.core.document.DocumentSource;
import org.grobid.core.engines.config.GrobidAnalysisConfig;
import org.grobid.core.factory.GrobidFactory;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class PdfStructureTest {

    @Ignore("Expensive test. Enable manually")
    // Verify that the PDF Structure information extracted is consistent with the TEI XML
    public void testPdfStructure() throws Exception {
        final Engine engine = GrobidFactory.getInstance().getEngine();
        GrobidAnalysisConfig config = new GrobidAnalysisConfig.GrobidAnalysisConfigBuilder().build();
        File pdf = new File("src/test/resources/test/Wang-paperAVE2008.pdf");
        DocumentSource documentSource = DocumentSource.fromPdf(pdf, -1, -1, false, true, true);

        Document doc = engine.getParsers().getFullTextParser().processing(documentSource, config);
        PdfStructureParser.PdfStructure pdfStructure = new PdfStructureParser().extractStructure(engine, doc);

        for (Map.Entry<String, ArrayList<PdfStructureParser.TextElement>> e : pdfStructure.getElements().getElementTypes().entrySet()) {
            for (PdfStructureParser.TextElement el : e.getValue()) {
                for (int i = 0; i < el.getSpans().size(); ++i) {
                    PdfStructureParser.Span current = el.getSpans().get(i);
                    if (current.getRight() == current.getLeft()) {
                        fail("Empty span " + current);
                    }
                    if (i < el.getSpans().size() - 1) {
                        PdfStructureParser.Span next = el.getSpans().get(i + 1);
                        if (next.getLeft() < current.getRight()) {
                            fail("Non-sequential spans of type" + e.getKey() + ": [" + current.getLeft() + "," + current.getRight() + "], ["
                                + next.getLeft() + "," + next.getRight() + "]");
                        }
                    }
                }
            }
        }

        List<PdfStructureParser.TextElement> bibItemsFromPdfStructure =
            pdfStructure.getElements().getElementTypes().get("<bibItem>");

        nu.xom.Document teiXmlDoc = new Builder().build(doc.getTei(), "");
        Nodes bibItemsFromTei = teiXmlDoc.query("//*[name()='div' and @type='references']//*[name()='biblStruct']");

        // Should have same number of bibliography entries
        assertEquals(bibItemsFromPdfStructure.size(), bibItemsFromTei.size());
        // For each bibliography entry, check the title and author list
        for (int i = 0; i < bibItemsFromTei.size(); ++i) {
            final String bibItemId = bibItemsFromPdfStructure.get(i).getTags().get("id");

            // Check titles
            String[] titleFromTei = bibItemsFromTei.get(i).query(".//*[name()='title']").get(0).getValue().split("\\s+");
            String[] titleFromPdfStructure =
                pdfStructure.textOf(pdfStructure.getElements().getElementTypes().get("<bibItem_title>")
                    .stream().filter(el -> el.getTags().get("id").equals(bibItemId))
                    .findFirst().get()).toArray(new String[0]);
            // Text should be the same, ignoring whitespace
            assertEquals(String.join("", titleFromTei), String.join("", titleFromPdfStructure));

            // Check authors
            List<String> authorsFromTei = new ArrayList<String>();
            Nodes authorNodes = bibItemsFromTei.get(i).query(".//*[name()='persName']//text()");
            for (int n = 0; n < authorNodes.size(); ++n) {
                authorsFromTei.addAll(Lists.newArrayList(authorNodes.get(n).getValue().split("[\\s\\p{Punct}]")));
            }
            HashSet<String> authorsFromPdfStructure = new HashSet<String>();
            authorsFromPdfStructure.addAll(
                pdfStructure.textOf(pdfStructure.getElements().getElementTypes().get("<bibItem_author>")
                    .stream().filter(el -> el.getTags().get("id").equals(bibItemId))
                    .findFirst().get()));
            // PDF Structure may contain punctuation tokens and the word 'and'.
            // Check for containment of the XML names
            assertTrue(authorsFromPdfStructure.containsAll(authorsFromTei));
        }
    }
}
