package com.quiz;

import java.time.Instant;
import java.util.UUID;

/** 한 문항에 대한 실제 사용자 응답이다. */
public record AnswerRecord(String questionId, String userAnswer, boolean correct, Instant answeredAt) {
    public AnswerRecord {
        if (questionId == null || !UUID.fromString(questionId).toString().equalsIgnoreCase(questionId)
                || userAnswer == null || userAnswer.isBlank() || answeredAt == null) {
            throw new IllegalArgumentException("풀이 기록의 필수 정보가 올바르지 않습니다.");
        }
    }

    public static AnswerRecord grade(SavedQuestion question, String answer, Instant at) {
        String normalized = question.type() == SavedQuestion.Type.OX
                ? answer.strip().toUpperCase(java.util.Locale.ROOT) : answer.strip();
        int selected;
        if (question.type() == SavedQuestion.Type.OX) {
            if (!normalized.equals("O") && !normalized.equals("X"))
                throw new IllegalArgumentException("O 또는 X를 입력하세요.");
            selected = normalized.equals("O") ? 0 : 1;
        } else {
            if (!normalized.matches("[1-4]"))
                throw new IllegalArgumentException("1~4 사이의 번호를 입력하세요.");
            selected = Integer.parseInt(normalized) - 1;
        }
        return new AnswerRecord(question.id(), normalized, selected == question.correctIndex(), at);
    }

    public void validateAgainst(SavedQuestion question) {
        if (!questionId.equals(question.id())
                || !grade(question, userAnswer, answeredAt).equals(this)) {
            throw new IllegalArgumentException("풀이 기록이 문제 ID 또는 정답과 맞지 않습니다.");
        }
    }
}
