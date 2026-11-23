package com.quiz;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 기본 테스트는 문서를 메모리에서 검증만 한다. -Dquiz.writeDocs=true 일 때만 명시적으로 쓴다. */
public class OxSampleReviewTest {
    private static final Path DOCUMENT = Path.of("docs/ox-sample-review.md");
    private record Counts(int o, int x) { int total() { return o + x; } }
    private record Review(OxReviewFixture.Sample sample, Map<String, Object> baseline,
            QuizGenerator.OxResult strict, Counts relaxed) { }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents() throws Exception {
        try (var input = OxSampleReviewTest.class.getResourceAsStream("/baseline/ox-seed7-before.json")) {
            assertNotNull("변경 전 기준은 고정 리소스로 제공되어야 합니다.", input);
            Map<String, Object> root = (Map<String, Object>) QuizJson.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(7, root.get("seed"));
            assertEquals("multipleChoice -> ox", root.get("callOrder"));
            return (List<Map<String, Object>>) (List<?>) root.get("documents");
        }
    }

    @Test public void reviewUsesFrozenBaselineAndWritesOnlyWhenExplicitlyRequested() throws Exception {
        byte[] before = Files.exists(DOCUMENT) ? Files.readAllBytes(DOCUMENT) : null;
        var beforeTime = Files.exists(DOCUMENT) ? Files.getLastModifiedTime(DOCUMENT) : null;
        List<Map<String, Object>> baseline = documents();
        List<OxReviewFixture.Sample> samples = OxReviewFixture.samples();
        assertEquals(baseline.size(), samples.size());
        List<Review> reviews = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            var sample = samples.get(i); var old = baseline.get(i);
            assertEquals(sample.key(), old.get("key"));
            assertEquals(old.get("sha256"), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sample.pdf()))));
            assertNull(sample.error()); assertNull(old.get("error"));
            for (Map<String, Object> row : rows(old)) {
                assertEquals(row.get("anchor"), row.get("sourceTerm"));
                assertTrue(String.valueOf(row.get("statement")).startsWith("원본 자료에서 다음 설명은 '"));
                assertTrue(String.valueOf(row.get("statement")).contains((Boolean) row.get("answer")
                        ? "직접 연결된 설명이다." : "직접 연결된 설명이 아니다."));
            }
            var strict = OxReviewFixture.generate(sample);
            OxAllocationTest.unique(strict);
            assertEquals(strict, OxReviewFixture.generate(sample));
            Counts relaxed = relaxedCounts(sample.extracted(), strict);
            assertTrue(relaxed.total() >= strict.questions().size());
            reviews.add(new Review(sample, old, strict, relaxed));
        }
        String text = render(reviews);
        assertTrue(text.contains("엄격(중복 금지)")); assertTrue(text.contains("완화(중복 허용 시)"));
        assertTrue(text.contains("A의 용어")); assertTrue(text.contains("제시 설명의 출처 용어"));
        if (Boolean.getBoolean("quiz.writeDocs")) Files.writeString(DOCUMENT, text);
        else if (before == null) assertFalse(Files.exists(DOCUMENT));
        else {
            assertArrayEquals(before, Files.readAllBytes(DOCUMENT));
            assertEquals(beforeTime, Files.getLastModifiedTime(DOCUMENT));
        }
    }

    @Test public void baselineCaptureCannotOverwriteTheFrozenResource() throws Exception {
        byte[] before = Files.readAllBytes(OxReviewFixture.BASELINE);
        try { OxReviewFixture.main(new String[0]); fail("기준 재캡처로 덮어쓰기 금지"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("덮어쓸 수 없습니다")); }
        assertArrayEquals(before, Files.readAllBytes(OxReviewFixture.BASELINE));
    }

    private static Counts relaxedCounts(TermExtractor.Result extracted, QuizGenerator.OxResult strict) {
        List<Term> eligible = new ArrayList<>();
        for (Term raw : extracted.terms()) {
            for (var q : strict.questions()) {
                for (Term candidate : List.of(q.original(), q.descriptionSource())) {
                    if (raw.term().equals(candidate.term()) && raw.page() == candidate.page() && !eligible.contains(candidate)) eligible.add(candidate);
                }
            }
        }
        assertEquals(strict.candidateCount(), eligible.size());
        int[] owner = new int[eligible.size()]; Arrays.fill(owner, -1);
        int maximum = 0;
        for (int a = 0; a < eligible.size(); a++) if (relaxedMatch(a, eligible, owner, new boolean[owner.length])) maximum++;
        int x = Math.min((eligible.size() + 1) / 2, maximum);
        return new Counts(eligible.size() - x, x);
    }

    private static boolean relaxedMatch(int a, List<Term> terms, int[] owner, boolean[] seen) {
        for (int b = 0; b < terms.size(); b++) if (!seen[b] && QuizGenerator.isDistractor(terms.get(a), terms.get(b))) {
            seen[b] = true;
            if (owner[b] < 0 || relaxedMatch(owner[b], terms, owner, seen)) { owner[b] = a; return true; }
        }
        return false;
    }

    private static Counts strictCounts(Review review) {
        int o = (int) review.strict().questions().stream().filter(QuizGenerator.OxQuestion::answer).count();
        return new Counts(o, review.strict().questions().size() - o);
    }
    private static String counts(Counts c) { return c.o() + " / " + c.x() + " (" + c.total() + ")"; }
    private static String cell(Object value) {
        return String.valueOf(value == null ? "없음" : value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("|", "&#124;").replace("\r", "").replace("\n", "<br>");
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> doc) {
        return (List<Map<String, Object>>) (List<?>) doc.get("questions");
    }
    private static String key(String name, int page) { return page + ":" + name; }

    private static String render(List<Review> reviews) {
        StringBuilder out = new StringBuilder("# O/X 실제 PDF 수정 전후 검토\n\n"
                + "seed=7, 매 범위마다 새 Random, 호출 순서=객관식 → O/X. 변경 전 결과는 고정 baseline 리소스에서 읽었다.\n\n"
                + "제품 동작은 엄격(중복 금지)이다. 완화(중복 허용 시)는 X끼리의 B 중복은 막되 O와 X 사이 설명 재사용을 허용한 비교용 숫자이며 문항을 제품에서 생성하지 않는다. 완화 목표 X는 후보 수의 절반(홀수는 올림)이다.\n\n"
                + "문자열 필터와 원문 연결은 검증했으나 의미상 배타성을 자동 증명하지는 않는다. 아래 A/B 조합을 원문과 대조해 유사 개념인지 검토해야 한다.\n\n"
                + "일반 mvn test는 이 문서를 쓰지 않는다. 재출력: `mvn test -Dtest=OxSampleReviewTest -Dquiz.writeDocs=true`. 50페이지를 넘는 PDF는 범위별로 처리했다.\n\n"
                + "## PDF별 합계\n\n| PDF | 엄격(중복 금지) O / X (총계) | 완화(중복 허용 시) O / X (총계) | 엄격으로 줄어든 수 |\n| --- | --- | --- | --- |\n");
        List<String> pdfs = new ArrayList<>();
        for (Review review : reviews) if (!pdfs.contains(review.sample().pdf().toString())) pdfs.add(review.sample().pdf().toString());
        for (String pdf : pdfs) {
            int so = 0, sx = 0, ro = 0, rx = 0;
            for (Review r : reviews) if (r.sample().pdf().toString().equals(pdf)) {
                Counts s = strictCounts(r); so += s.o(); sx += s.x(); ro += r.relaxed().o(); rx += r.relaxed().x();
            }
            out.append("| ").append(cell(pdf.replace('\\', '/'))).append(" | ").append(counts(new Counts(so, sx))).append(" | ")
                    .append(counts(new Counts(ro, rx))).append(" | ").append(ro + rx - so - sx).append(" |\n");
        }
        for (Review review : reviews) {
            out.append("\n## ").append(review.sample().key()).append("\n\n")
                    .append("- PDF SHA-256: `").append(review.baseline().get("sha256")).append("`\n")
                    .append("- 수정 전: ").append(review.baseline().get("summary")).append("\n")
                    .append("- 수정 후: ").append(review.strict().summary()).append("\n")
                    .append("- 엄격: ").append(counts(strictCounts(review))).append(" / 완화 비교: ").append(counts(review.relaxed())).append("\n\n")
                    .append("### 수정 후 출제 관계\n\n| A의 용어 | 제시 설명의 출처 용어(B 또는 A) | 정답 | A 기준 페이지 | A 기준 줄 | 설명 출처 페이지 |\n| --- | --- | --- | --- | --- | --- |\n");
            SavedQuizSet set = SavedQuizSet.fromGenerated(review.sample().pdf().toString(), review.sample().extracted(), review.strict().questions(), List.of());
            for (int i = 0; i < review.strict().questions().size(); i++) {
                var q = review.strict().questions().get(i);
                out.append("| ").append(cell(q.original().term())).append(" | ").append(cell(q.descriptionSource().term())).append(" | ")
                        .append(q.answer() ? "O" : "X").append(" | ").append(q.original().page()).append(" | ")
                        .append(cell(set.questions().get(i).lineNumber())).append(" | ").append(q.descriptionSource().page()).append(" |\n");
            }
            out.append("\n### 전체 질문·정답·해설 비교\n\n| A 식별(페이지:용어) | 수정 전 | 수정 후 |\n| --- | --- | --- |\n");
            Map<String, Map<String, Object>> old = new LinkedHashMap<>();
            Map<String, QuizGenerator.OxQuestion> current = new LinkedHashMap<>();
            for (Map<String, Object> row : rows(review.baseline())) old.put(key((String) row.get("anchor"), (Integer) row.get("anchorPage")), row);
            for (var q : review.strict().questions()) current.put(key(q.original().term(), q.original().page()), q);
            List<String> keys = new ArrayList<>(old.keySet());
            for (String k : current.keySet()) if (!keys.contains(k)) keys.add(k);
            for (String k : keys) {
                var before = old.get(k); var after = current.get(k);
                String oldText = before == null ? "제외" : before.get("statement") + "\n\n설명: " + before.get("presentedDescription")
                        + "\n정답: " + ((Boolean) before.get("answer") ? "O" : "X") + "\n해설: " + before.get("explanation");
                String newText = after == null ? "제외 (아래 진단 참조)" : after.statement() + "\n\n설명: " + after.presentedDescription()
                        + "\n정답: " + (after.answer() ? "O" : "X") + "\n해설: " + after.explanation();
                out.append("| ").append(cell(k)).append(" | ").append(cell(oldText)).append(" | ").append(cell(newText)).append(" |\n");
            }
            out.append("\n### 수정 후 진단\n\n");
            if (review.strict().skipped().isEmpty()) out.append("- 제외·배정 제한 없음\n");
            else review.strict().skipped().forEach(reason -> out.append("- ").append(reason).append("\n"));
        }
        return out.toString();
    }
}
