package org.grobid.service.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.grobid.core.document.Document;
import org.grobid.core.document.DocumentSource;
import org.grobid.core.engines.Engine;
import org.grobid.core.engines.PdfStructureParser;
import org.grobid.core.engines.config.GrobidAnalysisConfig;
import org.grobid.core.factory.GrobidPoolingFactory;
import org.grobid.core.utilities.IOUtilities;
import org.grobid.service.exceptions.GrobidServiceException;
import org.grobid.service.util.GrobidRestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.io.File;
import java.io.InputStream;
import java.util.NoSuchElementException;

/**
 * Web services consuming a file
 */
@Singleton
public class GrobidRestProcessPdfStructure {

    private static final Logger LOGGER = LoggerFactory.getLogger(GrobidRestProcessPdfStructure.class);

    @Inject
    public GrobidRestProcessPdfStructure() {

    }

    /**
     * Return all annotated token spans
     *
     * @param inputStream          the data of origin document
     * @return a set of text elements and their token spans
     */
    public Response processPdfStructure(final InputStream inputStream) throws Exception {
        LOGGER.debug(methodLogIn());

        String retVal = null;
        Response response = null;
        File originFile = null;
        Engine engine = null;
        try {
            engine = Engine.getEngine(true);
            // conservative check, if no engine is free in the pool a NoSuchElementException is normally thrown
            if (engine == null) {
                throw new GrobidServiceException(
                    "No GROBID engine available", Status.SERVICE_UNAVAILABLE);
            }

            originFile = IOUtilities.writeInputFile(inputStream);
            if (originFile == null) {
                LOGGER.error("The input file cannot be written.");
                throw new GrobidServiceException(
                    "The input file cannot be written.", Status.INTERNAL_SERVER_ERROR);
            }

            // starts conversion process
            GrobidAnalysisConfig config =
                GrobidAnalysisConfig.builder()
                    .build();

            DocumentSource documentSource = DocumentSource.fromPdf(originFile, -1, -1, false, true, true);
            Document doc = engine.getParsers().getFullTextParser().processing(documentSource, config);
            PdfStructureParser.PdfStructure structure = new PdfStructureParser().extractStructure(engine, doc);
            retVal = new ObjectMapper().writeValueAsString(structure);

            if (GrobidRestUtils.isResultNullOrEmpty(retVal)) {
                response = Response.status(Status.NO_CONTENT).build();
            } else {
                response = Response.status(Status.OK)
                    .entity(retVal)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON + "; charset=UTF-8")
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT")
                    .build();
            }
        } catch (NoSuchElementException nseExp) {
            LOGGER.error("Could not get an engine from the pool within configured time. Sending service unavailable.");
            response = Response.status(Status.SERVICE_UNAVAILABLE).build();
        } catch (Exception exp) {
            LOGGER.error("An unexpected exception occurs. ", exp);
            response = Response.status(Status.INTERNAL_SERVER_ERROR).entity(exp.getMessage()).build();
        } finally {
            if (engine != null) {
                GrobidPoolingFactory.returnEngine(engine);
            }

            if (originFile != null)
              IOUtilities.removeTempFile(originFile);
        }

        LOGGER.debug(methodLogOut());
        return response;
    }

    public String methodLogIn() {
        return ">> " + GrobidRestProcessPdfStructure.class.getName() + "." + Thread.currentThread().getStackTrace()[1].getMethodName();
    }

    public String methodLogOut() {
        return "<< " + GrobidRestProcessPdfStructure.class.getName() + "." + Thread.currentThread().getStackTrace()[1].getMethodName();
    }

}
