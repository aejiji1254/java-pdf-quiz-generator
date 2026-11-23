package com.quiz;

import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.Assert.*;

/** 변경 전 고정 캡처를 읽기만 한다. 갱신은 자동으로 허용하지 않는다. */
public class McBaselineSnapshotTest {
    @Test public void allExistingGenerationPathsMatchFrozenSeed7Snapshot() throws Exception {
        Map<String, Object> expected = baseline();
        assertEquals(7, expected.get("seed"));
        assertEquals(30, expected.get("forwardDefaultCount"));
        List<Map<String, Object>> documents = rows(expected.get("documents"));
        assertEquals(5, documents.size());
        // 재생보다 먼저 검사하여 입력 PDF 변경을 알고리즘 변경으로 오인하지 않는다.
        for (var doc : documents) assertPdfHash(Path.of((String) doc.get("pdf")), (String) doc.get("sha256"));
        Map<String, Object> actual = McBaselineCapture.current();
        List<Map<String, Object>> replayed = rows(actual.get("documents"));
        assertEquals(documents.size(), replayed.size());
        int caseCount = 0;
        Set<String> paths = new LinkedHashSet<>();
        for (int i = 0; i < documents.size(); i++) {
            var doc = documents.get(i);
            var replay = replayed.get(i);
            assertEquals(doc.get("key"), replay.get("key"));
            List<Map<String, Object>> cases = rows(doc.get("cases"));
            List<Map<String, Object>> replayCases = rows(replay.get("cases"));
            assertEquals(cases.size(), replayCases.size());
            for (int j = 0; j < cases.size(); j++) {
                var scenario = cases.get(j);
                String path = (String) scenario.get("path");
                paths.add(path); caseCount++;
                assertEquals(doc.get("key") + " / " + path, scenario, replayCases.get(j));
                assertEquals(3, ((List<?>) scenario.get("nextRandomLongs")).size());
                long mcCount = rows(scenario.get("questions")).stream()
                        .filter(q -> q.get("type").equals("MULTIPLE_CHOICE")).count();
                assertTrue("기존 기본 객관식 상한", mcCount <= 30);
                for (var q : rows(scenario.get("questions"))) {
                    assertFalse("랜덤 UUID는 비교하지 않는다", q.containsKey("id"));
                    assertEquals(Set.of("type", "question", "options", "correctIndex", "explanation",
                            "sourcePdf", "page", "lineNumber", "origin", "derivedFromQuestionId"), q.keySet());
                }
            }
        }
        assertEquals(31, caseCount);
        assertEquals(Set.of("PDF_RANGE", "PDF_WHOLE", "MANUAL", "MAIN_MENU_1", "MAIN_MENU_4",
                "MAIN_MENU_5", "MAIN_INTERACTIVE_5"), paths);
        assertEquals("메타데이터를 포함한 전체 캡처 일치", expected, actual);
    }

    @Test public void changedPdfHashFailsWithExplicitMessage() throws Exception {
        try {
            assertPdfHash(Path.of("sample.pdf"), "0".repeat(64));
            fail("변경된 PDF 해시를 받아들이면 안 됩니다.");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains("PDF SHA-256 mismatch: sample.pdf"));
            assertTrue(expected.getMessage().contains("다시 캡처하지 마세요"));
        }
    }

    @Test public void captureRefusesToOverwriteFrozenResource() throws Exception {
        byte[] before = Files.readAllBytes(McBaselineCapture.BASELINE);
        try {
            McBaselineCapture.capture();
            fail("기준 리소스 덮어쓰기를 허용하면 안 됩니다.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("덮어쓸 수 없습니다"));
        }
        assertArrayEquals(before, Files.readAllBytes(McBaselineCapture.BASELINE));
    }

    private static void assertPdfHash(Path pdf, String expected) throws Exception {
        assertTrue("기준 PDF가 없습니다: " + pdf, Files.isRegularFile(pdf));
        assertEquals("PDF SHA-256 mismatch: " + pdf + "; 변경 전 기준을 임의로 다시 캡처하지 마세요.",
                expected, McBaselineCapture.sha(pdf));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> baseline() throws Exception {
        try (var input = McBaselineSnapshotTest.class.getResourceAsStream("/baseline/mc-seed7-forward30-before.json")) {
            assertNotNull("고정 MC 기준 리소스가 필요합니다.", input);
            return (Map<String, Object>) QuizJson.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
