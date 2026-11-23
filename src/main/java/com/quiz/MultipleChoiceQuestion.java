package com.quiz;

import java.util.List;

public record MultipleChoiceQuestion(Type type, String prompt, List<String> options,
        int correctIndex, String explanation, Term original, Integer sourceLine) {
    public enum Type { CORRECT, INCORRECT }

    public MultipleChoiceQuestion(Type type, String prompt, List<String> options,
            int correctIndex, String explanation) {
        this(type, prompt, options, correctIndex, explanation, null, null);
    }

    public MultipleChoiceQuestion {
        options = List.copyOf(options);
        if (prompt == null || prompt.isBlank() || explanation == null || explanation.isBlank()
                || options.size() != 4 || options.stream().anyMatch(String::isBlank)
                || options.stream().map(SavedQuestion::optionKey).distinct().count() != 4
                || correctIndex < 0 || correctIndex >= 4
                || sourceLine != null && (sourceLine < 1 || original == null)) {
            throw new IllegalArgumentException("객관식은 서로 다른 보기 4개와 정답 1개가 필요합니다.");
        }
    }
}
