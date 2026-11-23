package com.quiz;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 기존 추출의 필드/문자열/hash 기준을 구현 전에 한 번만 고정한다. */
public class StructuredExtractionReview {
    static final Path LEGACY = Path.of("src/test/resources/baseline/extraction-legacy-before.json");
    static final Path STRUCTURED = Path.of("src/test/resources/baseline/structured-seed7-v1.json");
    static final Path DOCUMENT = Path.of("docs/structured-extraction-review.md");
    @Test public void legacyExtractionMatchesFrozenValues() throws Exception {
        List<Object> current = legacyValues();
        if (Boolean.getBoolean("quiz.captureLegacy")) {
            assertFalse("추출 LEGACY 기준 재캡처 금지", Files.exists(LEGACY));
            Files.writeString(LEGACY, QuizJson.write(current), StandardOpenOption.CREATE_NEW);
        } else {
            try (var stream = getClass().getResourceAsStream("/baseline/extraction-legacy-before.json")) {
                assertNotNull(stream);
                assertEquals(QuizJson.parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)), current);
            }
        }
    }
    static List<Object> legacyValues() throws Exception {
        List<Object> documents = new ArrayList<>();
        for (var sample : OxReviewFixture.samples()) {
            assertNull(sample.error());
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("key", sample.key()); row.put("sha256", McBaselineCapture.sha(sample.pdf()));
            row.put("result", sample.extracted().toString()); row.put("hashCode", sample.extracted().hashCode());
            documents.add(row);
        }
        return documents;
    }

    @Test public void structuredMatchesNewFrozenBaseline() throws Exception {
        Object current = StructuredPdfMeasurements.baseline(StructuredPdfMeasurements.samples());
        if (Boolean.getBoolean("quiz.captureStructured")) {
            assertFalse("STRUCTURED 기준 재캡처 금지", Files.exists(STRUCTURED));
            Files.writeString(STRUCTURED, QuizJson.write(current), StandardOpenOption.CREATE_NEW);
        } else try (var stream = getClass().getResourceAsStream("/baseline/structured-seed7-v1.json")) {
            assertNotNull(stream); assertEquals(QuizJson.parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)), current);
        }
    }
    @Test public void writeStructuredReview() throws Exception {
        byte[] before = Files.exists(DOCUMENT) ? Files.readAllBytes(DOCUMENT) : null;
        var time = Files.exists(DOCUMENT) ? Files.getLastModifiedTime(DOCUMENT) : null;
        String text = StructuredPdfMeasurements.markdown(StructuredPdfMeasurements.samples());
        if (Boolean.getBoolean("quiz.writeDocs")) Files.writeString(DOCUMENT, text);
        else if (before == null) assertFalse(Files.exists(DOCUMENT));
        else { assertArrayEquals(before,Files.readAllBytes(DOCUMENT)); assertEquals(time,Files.getLastModifiedTime(DOCUMENT)); }
    }
}
