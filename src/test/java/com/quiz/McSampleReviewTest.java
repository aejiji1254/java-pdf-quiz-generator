package com.quiz;

import com.quiz.GenerationOptions.Direction;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 측정 전용. -Dquiz.writeDocs=true 이외에는 docs 파일/시각을 바꾸지 않는다. */
public class McSampleReviewTest {
    private static final Path DOCUMENT = Path.of("docs/mc-sample-review.md");
    private record Item(int number, String type, Term anchor, String description, String question, List<String> options) { }
    private record Reappearance(int from, int to, String location, String description) { }

    @Test public void realPdfReviewMeasuresWithoutChangingGenerationOrWritingByDefault() throws Exception {
        byte[] before = Files.exists(DOCUMENT) ? Files.readAllBytes(DOCUMENT) : null;
        var time = Files.exists(DOCUMENT) ? Files.getLastModifiedTime(DOCUMENT) : null;
        String text = render();
        assertTrue(text.contains("유형 간 앵커 중복")); assertTrue(text.contains("재등장 사례 상위 10개"));
        assertTrue(text.contains("방향 | 앵커 | 오답 용어 | 정답 위치 | A 기준 페이지"));
        if (Boolean.getBoolean("quiz.writeDocs")) Files.writeString(DOCUMENT, text);
        else if (before == null) assertFalse(Files.exists(DOCUMENT));
        else { assertArrayEquals(before, Files.readAllBytes(DOCUMENT)); assertEquals(time, Files.getLastModifiedTime(DOCUMENT)); }
    }

    @Test public void measurementCountsWholeNormalizedDescriptionsPerTargetFieldAndExcludesSelf() {
        Term a = new Term("대리적 강화", "고유한 설명", 1), b = new Term("대리적강화", "다른 기능", 2);
        List<Item> items = List.of(new Item(1, "OX", a, "ＡＢ · Ｃ", "자기 질문", List.of("O", "X")),
                new Item(2, "MC", b, "다른 기능", "앞 abc 뒤", List.of("ab c", "일부 ab", "abcd", "별개")));
        var hits = reappearances(items);
        assertEquals(3, hits.size()); // 질문 1회 + 보기 2회. 같은 문항의 다른 필드도 각각 1회.
        assertTrue(hits.stream().allMatch(h -> h.from() == 1 && h.to() == 2));
        assertEquals(1, anchorOverlap(items));
    }

    private static String render() throws Exception {
        StringBuilder out = new StringBuilder("# 객관식 역방향·문항 수 검토\n\n")
                .append("seed 7, GUI 기본 섞기/객관식 30/OX 가능한 전부, PDF 경로 MC→OX. 동작 변경 없이 측정한다.\n\n")
                .append("단위: 유형 간 앵커 중복은 optionKey 후 모든 공백·문장부호를 제거한 용어 값의 교집합 개수다. ")
                .append("설명 재등장은 다른 문항의 질문·각 보기에 정규화한 설명 전체가 포함되는 필드 수다(질문 1개, 보기 각 1개를 별도로 센다). ")
                .append("자기 문항은 제외한다. 제시 설명은 OX의 실제 제시 설명, 역방향의 질문 설명, 정방향의 정답 설명 보기다. ")
                .append("상위 10개는 설명별 총 재등장 수 내림차순으로 정렬한 뒤 대상 문항·필드 사례를 최대 10행 표시한다.\n\n")
                .append("| PDF / 범위 | MC 정방향 | MC 역방향 | O/X | 유형 간 앵커 중복(개) | 설명 재등장(필드 회) |\n| --- | ---: | ---: | ---: | ---: | ---: |\n");
        StringBuilder details = new StringBuilder();
        for (var sample : OxReviewFixture.samples()) {
            assertNull(sample.error());
            QuizGenerator generator = new QuizGenerator(new Random(7));
            var mc = generator.generateMultipleChoice(sample.extracted(), 30, Direction.MIXED);
            var ox = generator.generateOxDetailed(sample.extracted());
            QuizGenerator replay = new QuizGenerator(new Random(7));
            assertEquals(mc, replay.generateMultipleChoice(sample.extracted(), 30, Direction.MIXED));
            assertEquals(ox, replay.generateOxDetailed(sample.extracted()));
            List<Item> items = new ArrayList<>();
            for (var q : ox.questions()) items.add(new Item(items.size() + 1, "OX", q.original(), q.presentedDescription(),
                    q.statement() + "\n\n설명: " + q.presentedDescription(), List.of("O", "X")));
            for (var q : mc.questions()) items.add(new Item(items.size() + 1, "MC", q.original(), q.original().description(), q.prompt(), q.options()));
            List<Reappearance> hits = reappearances(items);
            long reverse = mc.questions().stream().filter(QuizGenerator::reverse).count();
            out.append("| ").append(sample.key()).append(" | ").append(mc.questions().size() - reverse).append(" | ")
                    .append(reverse).append(" | ").append(ox.questions().size()).append(" | ").append(anchorOverlap(items))
                    .append(" | ").append(hits.size()).append(" |\n");
            details.append("\n## ").append(sample.key()).append("\n\n")
                    .append("PDF SHA-256: `").append(McBaselineCapture.sha(sample.pdf())).append("`\n\n")
                    .append("| 문항 | 방향 | 앵커 | 오답 용어 | 정답 위치 | A 기준 페이지 |\n| --- | --- | --- | --- | ---: | --- |\n");
            for (int i = 0; i < mc.questions().size(); i++) {
                var q = mc.questions().get(i);
                List<String> distractors = new ArrayList<>();
                for (int option = 0; option < 4; option++) if (option != q.correctIndex()) {
                    String text = q.options().get(option);
                    // 전체 설명은 이미 생성된 해설에 보존된다. 표의 출처 용어는 입력의 실제 연결을 사용한다.
                    String term = QuizGenerator.reverse(q) ? text : java.util.stream.Stream.concat(
                            mc.questions().stream().map(MultipleChoiceQuestion::original), sample.extracted().terms().stream())
                            .filter(t -> t.description().equals(text)).map(Term::term).findFirst().orElseThrow(
                                    () -> new AssertionError("오답 설명의 출처 용어를 찾지 못함: " + text));
                    distractors.add(term);
                }
                details.append("| ").append(ox.questions().size() + i + 1).append(" | ").append(QuizGenerator.reverse(q) ? "역방향" : "정방향")
                        .append(" | ").append(cell(q.original().term())).append(" | ").append(cell(String.join(", ", distractors)))
                        .append(" | ").append(q.correctIndex() + 1).append(" | ").append(page(q.original())).append(" |\n");
            }
            details.append("\n### 재등장 사례 상위 10개\n\n| 설명 출처 문항 | 재등장 대상 문항 | 대상 필드 | 이 설명의 총 재등장 수 | 설명 |\n| ---: | ---: | --- | ---: | --- |\n");
            Map<Integer, Long> counts = new LinkedHashMap<>();
            for (var hit : hits) counts.merge(hit.from(), 1L, Long::sum);
            hits.sort(Comparator.<Reappearance>comparingLong(h -> -counts.get(h.from()))
                    .thenComparingInt(Reappearance::from).thenComparingInt(Reappearance::to).thenComparing(Reappearance::location));
            for (var hit : hits.stream().limit(10).toList()) details.append("| ").append(hit.from()).append(" | ").append(hit.to())
                    .append(" | ").append(hit.location()).append(" | ").append(counts.get(hit.from())).append(" | ")
                    .append(cell(hit.description())).append(" |\n");
            if (hits.isEmpty()) details.append("| — | — | 재등장 없음 | 0 | — |\n");
            details.append("\n### 문항 전체(문항 번호는 O/X 다음 객관식)\n");
            for (Item item : items) details.append("\n").append(item.number()).append(". **").append(item.type()).append(" / ")
                    .append(cell(item.anchor().term())).append("** — ").append(cell(item.question())).append("\n\n   보기: ")
                    .append(cell(String.join(" / ", item.options()))).append("\n");
            details.append("\n### 생성 진단\n\n");
            for (String reason : mc.skipped()) details.append("- ").append(cell(reason)).append("\n");
        }
        return out.append(details).toString();
    }
    private static List<Reappearance> reappearances(List<Item> items) {
        List<Reappearance> hits = new ArrayList<>();
        for (Item from : items) for (Item to : items) if (from.number() != to.number()) {
            String key = QuizGenerator.exposureKey(from.description());
            if (key.isEmpty()) continue;
            if (QuizGenerator.exposureKey(to.question()).contains(key)) hits.add(new Reappearance(from.number(), to.number(), "질문", from.description()));
            for (int i = 0; i < to.options().size(); i++) if (QuizGenerator.exposureKey(to.options().get(i)).contains(key))
                hits.add(new Reappearance(from.number(), to.number(), "보기 " + (i + 1), from.description()));
        }
        return hits;
    }
    private static int anchorOverlap(List<Item> items) {
        Set<String> ox = new LinkedHashSet<>(), mc = new LinkedHashSet<>();
        for (Item item : items) (item.type().equals("OX") ? ox : mc).add(QuizGenerator.exposureKey(item.anchor().term()));
        ox.retainAll(mc); return ox.size();
    }
    private static String cell(String text) { return text.replace("|", "\\|").replace("\n", "<br>"); }
    private static String page(Term term) { return term.page() == 0 ? "페이지 없음" : term.page() + "페이지"; }
}
