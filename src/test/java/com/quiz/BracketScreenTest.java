package com.quiz;

import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class BracketScreenTest {
    @ClassRule public static TemporaryFolder temporary = new TemporaryFolder();
    private static BracketRuleTestSupport support;
    private static final String TEXT = "푸른 결정을 일정한 간격으로 옮기는 절차이다.";
    @BeforeClass public static void compileDisabledRuleOnlyInTest() throws Exception {
        support = new BracketRuleTestSupport(temporary.newFolder().toPath());
    }
    @AfterClass public static void closeDisabledLoader() throws Exception { if (support != null) support.close(); }
    @Test public void openingClosingReversedAndCrossedBracketsAreRejectedInEitherField() throws Exception {
        for (String mark : List.of("(", ")", "][", "}{", ")(", "([)]", "[", "}")) {
            Term name = new Term("루메라" + mark, TEXT, 1), description = new Term("루메라", TEXT + mark, 1);
            assertTrue(BracketRuleTestSupport.afterReason(name).contains(BracketRuleTestSupport.REASON));
            assertTrue(BracketRuleTestSupport.afterReason(description).contains(BracketRuleTestSupport.REASON));
        }
        assertEquals(BracketRuleTestSupport.REASON, BracketRuleTestSupport.afterReason(new Term("루메라(", TEXT + ")", 1)));
    }
    @Test public void nestedBalancedAbsentAndQuotesDoNotAddBracketReason() throws Exception {
        for (String suffix : List.of("", "(가[나{다}])", "'", "\"", "“", "[가](나){다}")) {
            Term term = new Term("루메라" + suffix, TEXT, 1);
            assertEquals(support.beforeReason(term), BracketRuleTestSupport.afterReason(term));
        }
        // 균형 잡힌 중괄호 설명은 기존 코드형 문자열 규칙으로 계속 제외된다.
        Term braces = new Term("루메라", TEXT + "{가}", 1);
        assertEquals("설명에 URL 또는 코드형 문자열이 포함됨", BracketRuleTestSupport.afterReason(braces));
    }
    @Test public void fullwidthBracketsAreCheckedWithoutChangingTheTerm() throws Exception {
        Term balanced = new Term("루메라（［｛가｝］）", TEXT, 1);
        assertNull(BracketRuleTestSupport.afterReason(balanced));
        for (String suffix : List.of("（", "）", "］［", "（］"))
            assertEquals(BracketRuleTestSupport.REASON, BracketRuleTestSupport.afterReason(new Term("루메라" + suffix, TEXT, 1)));
        assertEquals("루메라（［｛가｝］）", balanced.term());
    }
    @Test public void numberedItemsDistinguishExistingExclusionFromNewFalsePositiveRisk() throws Exception {
        Term existing = new Term("루메라", "푸른 결정의 이동 절차 1) 항목을 수행한다.", 1);
        assertEquals("설명에 다른 항목 번호가 들어 있음", support.beforeReason(existing));
        assertEquals(support.beforeReason(existing) + "; " + BracketRuleTestSupport.REASON, BracketRuleTestSupport.afterReason(existing));
        Term terminal = new Term("루메라", "푸른 결정의 이동 순서 1)", 1);
        assertNull(support.beforeReason(terminal));
        assertEquals(BracketRuleTestSupport.REASON, BracketRuleTestSupport.afterReason(terminal));
        var extracted = new TermExtractor().extract(List.of("1) 루메라: " + TEXT));
        assertEquals("루메라", extracted.terms().getFirst().term());
        assertEquals(support.withoutRule(extracted), support.withRule(extracted));
    }
    @Test public void syntheticInputsOnlyLoseCandidatesForTheBracketReason() throws Exception {
        List<Term> terms = List.of(new Term("루메라", TEXT, 1), new Term("베로니(", "은빛 조각을 보관하는 저장 공간이다.", 2),
                new Term("사피론[가]", "보랏빛 구슬을 분류하는 작업 방법이다.", 3), new Term("델로스", "황금빛 가루를 운반하는 순서이다.)", 4));
        support.assertMonotonic(new TermExtractor.Result(terms, List.of()));
    }
    @Test public void existingPdfsInBothModesAndManualInputsAreMonotonic() throws Exception {
        for (var sample : OxReviewFixture.samples()) {
            assertNull(sample.error());
            for (var mode : TermExtractor.Mode.values()) {
                var input = new TermExtractor().extract(new PdfReader().readRange(sample.pdf(), sample.first(), sample.last()), sample.first(), mode);
                support.assertMonotonic(input);
            }
            Map<String, Term> unique = new LinkedHashMap<>();
            for (Term t : sample.extracted().terms()) unique.putIfAbsent(t.term().strip().toLowerCase(Locale.ROOT), new Term(t.term(), t.description(), 0));
            support.assertMonotonic(new TermExtractor.Result(new ArrayList<>(unique.values()), List.of()));
        }
    }
    @Test public void restoredInterruptedDescriptionStillReportsTheActualBracketReason() throws Exception {
        Term term = new Term("루메라", "푸른 결정을 옮기는 절차(이다 뒤에 다른 문장이 혼입됨", 1);
        var title = new TermExtractor.SourceLine(1, 1, "루메라");
        var evidence = new TermExtractor.Evidence(term, title, List.of(new TermExtractor.SourceLine(1, 2, ": 푸른 결정을 옮기는 절차(이다")),
                List.of(new TermExtractor.SourceLine(1, 3, "___")));
        var input = new TermExtractor.Result(List.of(term), List.of(), List.of(evidence));
        assertEquals(1, support.withoutRule(input).size()); assertTrue(support.withRule(input).isEmpty());
        assertTrue(new QuizGenerator(new Random(7)).generateOxDetailed(input).skipped().stream().anyMatch(s -> s.contains(BracketRuleTestSupport.REASON)));
        support.assertMonotonic(input);
    }
}
