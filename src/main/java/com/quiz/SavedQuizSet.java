package com.quiz;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

public record SavedQuizSet(String id, String sourcePdf, List<SavedQuestion> questions,
        String title, String subject, Instant createdAt, String derivedFromSetId) {
    public SavedQuizSet(String id, String sourcePdf, List<SavedQuestion> questions) {
        // 저장 전 호환 생성자는 안정적인 미저장 시각을 사용하며 실제 저장 경로가 Clock 시각을 부여한다.
        this(id, sourcePdf, questions, QuizMetadata.defaultTitle(sourcePdf), "", Instant.EPOCH, null);
    }

    public SavedQuizSet withQuestions(List<SavedQuestion> questions) {
        return new SavedQuizSet(id, sourcePdf, questions, title, subject, createdAt, derivedFromSetId);
    }

    public SavedQuizSet forSave(String title, String subject, Instant at) {
        return new SavedQuizSet(id, sourcePdf, questions, title, subject, at, derivedFromSetId);
    }

    public SavedQuizSet {
        title = QuizMetadata.title(title);
        subject = QuizMetadata.subject(subject);
        if (createdAt == null || derivedFromSetId != null
                && !UUID.fromString(derivedFromSetId).toString().equalsIgnoreCase(derivedFromSetId))
            throw new IllegalArgumentException("세트 생성일·출처 연결 정보가 올바르지 않습니다.");
        if (id == null || !UUID.fromString(id).toString().equalsIgnoreCase(id)
                || sourcePdf == null || sourcePdf.isBlank() || questions == null) {
            throw new IllegalArgumentException("문제 세트의 필수 정보가 없습니다.");
        }
        questions = List.copyOf(questions);
        Set<String> ids = new HashSet<>();
        for (SavedQuestion question : questions) {
            if (!ids.add(question.id()) || !sourcePdf.equals(question.sourcePdf())) {
                throw new IllegalArgumentException("문제 ID가 중복되었거나 출처 PDF가 다릅니다.");
            }
        }
    }

    public static SavedQuizSet fromGenerated(String sourcePdf, TermExtractor.Result extracted,
            List<QuizGenerator.OxQuestion> ox, List<MultipleChoiceQuestion> multipleChoice) {
        Map<Term, Integer> lines = new IdentityHashMap<>();
        for (TermExtractor.Evidence evidence : extracted.evidence()) {
            if (evidence.termLine() != null) {
                lines.put(evidence.term(), evidence.termLine().lineNumber());
            }
        }
        List<SavedQuestion> questions = new ArrayList<>();
        for (QuizGenerator.OxQuestion item : ox) {
            Term term = item.original();
            Integer sourceLine = lines.get(term);
            if (sourceLine == null) {
                sourceLine = extracted.evidence().stream()
                        .filter(e -> e.term().page() == term.page()
                                && e.term().term().equals(term.term()) && e.termLine() != null)
                        .map(e -> e.termLine().lineNumber()).findFirst().orElse(null);
            }
            questions.add(new SavedQuestion(UUID.randomUUID().toString(), SavedQuestion.Type.OX,
                    item.statement() + "\n\n설명: " + item.presentedDescription(),
                    List.of("O", "X"), item.answer() ? 0 : 1,
                    item.explanation(), sourcePdf,
                    term.page() > 0 ? term.page() : null, sourceLine, SavedQuestion.Origin.GENERATED, null));
        }
        for (MultipleChoiceQuestion item : multipleChoice) {
            Term term = item.original();
            questions.add(new SavedQuestion(UUID.randomUUID().toString(),
                    SavedQuestion.Type.MULTIPLE_CHOICE, item.prompt(), item.options(),
                    item.correctIndex(), item.explanation(), sourcePdf,
                    term == null || term.page() <= 0 ? null : term.page(), item.sourceLine(),
                    SavedQuestion.Origin.GENERATED, null));
        }
        return new SavedQuizSet(UUID.randomUUID().toString(), sourcePdf, questions);
    }
}
