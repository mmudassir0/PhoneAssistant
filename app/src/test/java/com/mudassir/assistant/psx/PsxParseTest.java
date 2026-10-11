package com.mudassir.assistant.psx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

/** Real PSX pages captured on 10 Oct 2026 (shared with the psx-mobile app's parser tests). */
public class PsxParseTest {

    private String page(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/psx/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void companyQuote() throws Exception {
        PsxParse.Quote q = PsxParse.company("LUCK", page("company_LUCK.html"));
        assertNotNull(q);
        assertEquals("Lucky Cement Limited", q.name);
        assertEquals(405.38, q.close, 1e-9);
        assertEquals(-1.74, q.change, 1e-9);
        assertEquals(-0.43, q.changePct, 1e-9);
    }

    @Test
    public void indices() throws Exception {
        Map<String, PsxParse.IndexQuote> idx = PsxParse.indices(page("indices.html"));
        assertEquals(167089.27, idx.get("KSE100").current, 1e-9);
        assertNotNull(idx.get("KMI30"));
    }

    @Test
    public void unknownSymbolHasNoQuote() {
        assertNull(PsxParse.company("XYZ", "<html><body>Not found</body></html>"));
    }
}
