package com.quiz;

import org.junit.Test;

/** 일반 mvn test에서도 두 모드의 고정 기준과 기존 Term 보존을 항상 검증한다. */
public class StructuredExtractionPdfTest {
    @Test public void legacyExtractionFieldsTextAndHashStillMatchPreChangeCapture() throws Exception {
        new StructuredExtractionReview().legacyExtractionMatchesFrozenValues();
    }
    @Test public void structuredPdfTermsEvidenceQuestionsAndRandomTailMatchNewCapture() throws Exception {
        new StructuredExtractionReview().structuredMatchesNewFrozenBaseline();
    }
}
