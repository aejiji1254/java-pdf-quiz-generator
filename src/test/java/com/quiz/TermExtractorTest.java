package com.quiz;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class TermExtractorTest {
    @Test public void rangeOffsetKeepsOriginalPageInEvidence() {
        TermExtractor.Result result = new TermExtractor().extract(
                List.of("캐시\n: 자주 쓰는 자료를 보관한다"), 42);
        assertEquals(42, result.terms().get(0).page());
        assertEquals(42, result.evidence().get(0).termLine().page());
        assertEquals(1, result.evidence().get(0).termLine().lineNumber());
        assertEquals(42, result.evidence().get(0).descriptionLines().get(0).page());
    }

    @Test public void explicitDefinitionsStaySeparateAndKeepSourceLines() {
        TermExtractor.Result result = new TermExtractor().extract(List.of(
                "캐시: 자주 쓰는 데이터를 임시로 보관한다\n"
                + "버퍼: 입출력 속도 차이를 완화한다\n\n"
                + "3) 인덱스\n: 검색 위치를 빠르게 찾도록 돕는다\n"
                + "• 트랜잭션\n: 작업을 하나로 묶어 처리한다"));
        assertEquals(4, result.candidateCount());
        assertEquals(4, result.terms().size());
        assertEquals("자주 쓰는 데이터를 임시로 보관한다", result.terms().get(0).description());
        assertEquals("입출력 속도 차이를 완화한다", result.terms().get(1).description());
        assertEquals("인덱스", result.terms().get(2).term());
        assertEquals("트랜잭션", result.terms().get(3).term());
        assertEquals(1, result.evidence().get(0).termLine().lineNumber());
        assertEquals(4, result.evidence().get(2).termLine().lineNumber());
    }

    @Test public void outOfOrderDescriptionCannotBeAttachedToFollowingTitle() {
        TermExtractor.Result result = new TermExtractor().extract(List.of(
                ": 먼저 추출된 설명\n캐시\n다음 설명도 순서가 뒤섞임"));
        assertTrue(result.terms().isEmpty());
        assertEquals(0, result.candidateCount());
        assertTrue(result.warnings().stream().anyMatch(w ->
                w.line().equals(": 먼저 추출된 설명")));
    }

    @Test public void extractsExplicitInlineDefinitionsButRejectsColonTableRows() {
        TermExtractor.Result result = new TermExtractor().extract(List.of(
                "캐시: 자주 쓰는 데이터를 빠르게 제공한다",
                "메모리: 데이터를 저장한다\n열1: 열2: 열3\n설명 없는 제목"));
        assertEquals(List.of(new Term("캐시", "자주 쓰는 데이터를 빠르게 제공한다", 1),
                new Term("메모리", "데이터를 저장한다", 2)), result.terms());
        assertTrue(result.warnings().stream().anyMatch(w -> w.line().equals("열1: 열2: 열3")));
    }

    @Test
    public void joinsDescriptionAndContinuationAcrossBlankLines() {
        TermExtractor.Result result = new TermExtractor().extract(
                List.of("용어\n\n: 첫 설명\n\n이어지는 줄"));
        assertEquals(List.of(new Term("용어", "첫 설명 이어지는 줄", 1)), result.terms());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    public void joinsTwoDescriptionsForOneTerm() {
        assertEquals(List.of(new Term("용어", "설명 하나 설명 둘", 1)),
                new TermExtractor().extract(List.of("• 용어\n: 설명 하나\n: 설명 둘")).terms());
    }

    @Test public void recognizesCommonBulletsInSeparatedAndInlineDefinitions() {
        String[] bullets = {"•", "▪", "●", "■", "◦", "○", "·", "-"};
        for (String bullet : bullets) {
            TermExtractor.Result separated = new TermExtractor().extract(List.of(
                    bullet + " L2 캐시\n: 반복 데이터를 빠르게 제공하는 임시 저장소"));
            assertEquals(bullet, List.of(new Term("L2 캐시",
                    "반복 데이터를 빠르게 제공하는 임시 저장소", 1)), separated.terms());
            assertEquals(1, separated.evidence().get(0).termLine().lineNumber());
            TermExtractor.Result inline = new TermExtractor().extract(List.of(
                    bullet + " L2 캐시: 반복 데이터를 빠르게 제공하는 임시 저장소"));
            assertEquals(bullet, separated.terms(), inline.terms());
        }
        assertEquals("C++ 메모리", new TermExtractor().extract(List.of(
                "• C++ 메모리\n: 데이터를 임시 보관하는 공간")).terms().get(0).term());
    }

    @Test
    public void skipsUnderscoresButEndsAtExamplesAndNumbers() {
        TermExtractor.Result result = new TermExtractor().extract(List.of(
                "- 첫 용어\n: 설명\n___ 빈칸\n계속\nex. 예시\n예시의 후속 줄\n: 예시 설명\n"
                + "1) 둘째 용어\n: 둘째 설명\n123\n연결하면 안 되는 줄"));
        assertEquals(List.of(new Term("첫 용어", "설명 계속", 1),
                new Term("둘째 용어", "둘째 설명", 1)), result.terms());
        for (String excluded : List.of("___ 빈칸", "ex. 예시", "예시의 후속 줄", ": 예시 설명", "123")) {
            assertTrue(excluded, result.warnings().stream().anyMatch(w -> w.line().equals(excluded)));
        }
    }

    @Test
    public void recognizesNumberMarkersAndKeepsUnmarkedContinuation() {
        TermExtractor.Result result = new TermExtractor().extract(List.of(
                "1. 하나\n: 설명 일\n기호 없는 문장\n: 추가 설명\n"
                + "2) 둘\n: 설명 이\n(3) 셋\n: 설명 삼\n④ 넷\n: 설명 사"));
        assertEquals(List.of(new Term("하나", "설명 일 기호 없는 문장 추가 설명", 1),
                new Term("둘", "설명 이", 1), new Term("셋", "설명 삼", 1),
                new Term("넷", "설명 사", 1)), result.terms());
    }

    @Test
    public void repeatedShortLinesRequireThreeDistinctPagesAndEndItem() {
        String shortLine = "가".repeat(20);
        String longLine = "나".repeat(21);
        TermExtractor.Result result = new TermExtractor().extract(List.of(
                "첫 용어\n: 설명\n" + shortLine + "\n뒤 문장",
                shortLine + "\n" + shortLine + "\n" + longLine + "\n두 번 반복",
                shortLine + "\n" + longLine + "\n두 번 반복",
                longLine));
        assertEquals(List.of(new Term("첫 용어", "설명", 1)), result.terms());
        assertEquals(4, result.warnings().stream().filter(w ->
                w.reason().equals("여러 페이지에 반복되는 줄")).count());
        assertFalse(result.warnings().stream().anyMatch(w ->
                w.reason().equals("여러 페이지에 반복되는 줄")
                && (w.line().equals(longLine) || w.line().equals("두 번 반복"))));
    }

    @Test
    public void emptyInputReturnsEmptyTermsAndWarnings() {
        TermExtractor.Result result = new TermExtractor().extract(List.of());
        assertTrue(result.terms().isEmpty());
        assertTrue(result.warnings().isEmpty());
        assertTrue(new TermExtractor().extract(List.of("", " \n")).terms().isEmpty());
    }

    @Test
    public void extractsFourSamplePagesWithOriginalPageNumbers() {
        List<String> pages = new ArrayList<>(Collections.nCopies(20, ""));
        pages.set(8, """
                9
                • 고전적 조건화
                : 행동을 유발하는 힘이 없는 중성자극(종소리)에
                반응유발능력(먹이) 불어넣어 조건자극으로 변화시키는 과정
                • 반응적 행동
                : 고전적 조건화에 의해 형성된 행동
                : 인간이 특정자극에 대해 자동적으로 반응을 보이는 것
                ex. 수업시간 교수질문에 초조해하는 행동?
                생애주기와 노화
                """);
        pages.set(14, """
                15
                - 조작적 조건화
                : 행동의 원인과 결과 발견,  원인인 자극조정
                그 결과인 반응 통제
                - 조작적 행동
                : 도구적 조건화 또는 조작적 조건화에 의해 습득된 행동
                특정행동에 따르는 결과가 다음행동의 원인이 되며
                행동은 결과, 즉 보상과 벌에 의해 유지 또는 통제된다는 것
                생애주기와 노화
                """);
        pages.set(17, """
                18
                2) 대리적 조건화와 학습
                - 대리적 강화
                : 모델이 하는 행동을 관찰하고 강화 받는 것
                - 대리적 조건화에 의한 학습
                : 타인의 경험을 관찰함으로 이미 알고 있는 행동이 강화됨
                3) _______________
                - 인간이 성격은 개인적, 행동적, 환경적 요소들간의
                지속적 상호작용에 의해 발달됨
                생애주기와 노화
                """);
        pages.set(19, """
                20
                4) 자아강화와 자아 효능감
                - 자아강화
                : 각 개인이 수행 또는 성취의 기준을 설정
                자신의 기대를 달성하거나 또는 그 수준에 못 미치는 경우
                자신에게 보상 또는 벌을 내린다는 개념
                : 그 기준은 “학습”을 통해 획득
                -자아효능감
                : 자신의 특정행동을 성공적으로 수행할 수 있다는 신념
                : 행동할지 여부, 행동 이후 상황, 선택행동을 결정하는 등
                개인의 사고와 정서에 영향을 미침
                생애주기와 노화
                """);

        TermExtractor.Result result = new TermExtractor().extract(pages);
        assertEquals(List.of(
                new Term("고전적 조건화", "행동을 유발하는 힘이 없는 중성자극(종소리)에 반응유발능력(먹이) 불어넣어 조건자극으로 변화시키는 과정", 9),
                new Term("반응적 행동", "고전적 조건화에 의해 형성된 행동 인간이 특정자극에 대해 자동적으로 반응을 보이는 것", 9),
                new Term("조작적 조건화", "행동의 원인과 결과 발견,  원인인 자극조정 그 결과인 반응 통제", 15),
                new Term("조작적 행동", "도구적 조건화 또는 조작적 조건화에 의해 습득된 행동 특정행동에 따르는 결과가 다음행동의 원인이 되며 행동은 결과, 즉 보상과 벌에 의해 유지 또는 통제된다는 것", 15),
                new Term("대리적 강화", "모델이 하는 행동을 관찰하고 강화 받는 것", 18),
                new Term("대리적 조건화에 의한 학습", "타인의 경험을 관찰함으로 이미 알고 있는 행동이 강화됨 - 인간이 성격은 개인적, 행동적, 환경적 요소들간의 지속적 상호작용에 의해 발달됨", 18),
                new Term("자아강화", "각 개인이 수행 또는 성취의 기준을 설정 자신의 기대를 달성하거나 또는 그 수준에 못 미치는 경우 자신에게 보상 또는 벌을 내린다는 개념 그 기준은 “학습”을 통해 획득", 20),
                new Term("자아효능감", "자신의 특정행동을 성공적으로 수행할 수 있다는 신념 행동할지 여부, 행동 이후 상황, 선택행동을 결정하는 등 개인의 사고와 정서에 영향을 미침", 20)
        ), result.terms());
        assertEquals(4, result.warnings().stream()
                .filter(w -> w.reason().equals("여러 페이지에 반복되는 줄")).count());
        assertTrue(result.warnings().contains(new TermExtractor.Warning(18,
                "3) _______________", "밑줄 빈칸이 있는 줄")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.page() == 9
                && w.line().startsWith("ex.") && w.reason().equals("예시 줄")));
        assertEquals(4, result.warnings().stream()
                .filter(w -> w.reason().equals("숫자만 있는 줄")).count());
    }

    @Test
    public void rejectsOrphanDescriptionsAndDoesNotJoinAcrossExcludedLinesOrPages() {
        TermExtractor.Result result = new TermExtractor().extract(List.of(
                "용어\n___\n: 잘못된 설명\n- 빈 설명\n:",
                ": 이전 페이지 설명\n123\nex. 예시"));
        assertTrue(result.terms().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w ->
                w.line().equals(": 잘못된 설명") && w.reason().equals("연결할 용어가 없는 줄")));
        assertTrue(result.warnings().stream().anyMatch(w ->
                w.page() == 2 && w.line().equals(": 이전 페이지 설명")));
        assertTrue(result.warnings().stream().anyMatch(w ->
                w.reason().equals("유효한 설명이 없는 용어")));
    }

    @Test
    public void repetitionRequiresDifferentPagesAndBlankLinesAreIgnored() {
        TermExtractor.Result result = new TermExtractor().extract(List.of(
                " • - 용어\n\n: 설명\n: 설명\n이어지는 줄"));
        assertEquals(List.of(new Term("용어", "설명 설명 이어지는 줄", 1)), result.terms());
        assertTrue(result.warnings().isEmpty());
        assertTrue(new TermExtractor().extract(List.of()).terms().isEmpty());
    }
    @Test
    public void preservesOriginalLineNumbersAndInterruptedDescriptionRelation() {
        TermExtractor.Result result = new TermExtractor().extract(List.of(
                "제목\n\n- 개념\n: 첫 설명\n이어지는 설명\n3) ___\n후속 문장"));
        assertEquals(1, result.terms().size());
        assertEquals(1, result.evidence().size());
        TermExtractor.Evidence evidence = result.evidence().get(0);
        assertEquals(result.terms().get(0), evidence.term());
        assertEquals(new TermExtractor.SourceLine(1, 3, "- 개념"), evidence.termLine());
        assertEquals(List.of(new TermExtractor.SourceLine(1, 4, ": 첫 설명"),
                new TermExtractor.SourceLine(1, 5, "이어지는 설명"),
                new TermExtractor.SourceLine(1, 7, "후속 문장")),
                evidence.descriptionLines());
        assertEquals(List.of(new TermExtractor.SourceLine(1, 6, "3) ___")),
                evidence.interruptions());
    }

}
