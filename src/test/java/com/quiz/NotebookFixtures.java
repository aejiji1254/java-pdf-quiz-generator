package com.quiz;

import com.quiz.gui.QuizWorkflow;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class NotebookFixtures {
    public static final String SOURCE = "C:\\자료\\notes.pdf";
    public static final Instant TIME = Instant.parse("2026-11-22T00:00:00Z");
    public static String id(String name) { return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
    public static SavedQuestion question(String name, String parent) {
        return new SavedQuestion(id(name), SavedQuestion.Type.MULTIPLE_CHOICE, "원문 질문 " + name.replaceAll("[0-9]+", ""),
                List.of("정답 A", "보기 B", "보기 C", "보기 D"), 0, "원문 해설", SOURCE, 2, 3, SavedQuestion.Origin.GENERATED, parent);
    }
    public static SavedQuestion linked(String name, SavedQuestion parent) {
        return new SavedQuestion(id(name), parent.type(), parent.question(), parent.options(), parent.correctIndex(),
                "변경 가능한 해설", SOURCE, 5, 6, parent.origin(), parent.id());
    }
    public static SavedQuizSet set(String name, Instant created, SavedQuestion... questions) {
        return new SavedQuizSet(id("set-" + name), SOURCE, List.of(questions), "세트 " + name, name.startsWith("B") ? "과목 B" : "과목 A", created, null);
    }
    public static QuizResult result(String name, SavedQuizSet set, SavedQuestion question, boolean correct, Instant time) {
        int answer = correct ? question.correctIndex() + 1 : (question.correctIndex() + 1) % 4 + 1;
        return new QuizResult(id("result-" + name), set.id(), set.sourcePdf(), null, set.questions().size(), time,
                List.of(AnswerRecord.grade(question, String.valueOf(answer), time)));
    }
    public static QuizWorkflow workflow(Path root) {
        return new QuizWorkflow(new QuizRepository(root.resolve("quizzes")), new QuizResultRepository(root.resolve("results")),
                new ManualTermRepository(root.resolve("manual")), Clock.fixed(TIME, ZoneOffset.UTC), QuizGenerator::new);
    }
    public static WrongAnswerNotebook.Snapshot aggregate(List<SavedQuizSet> sets, QuizResult... results) {
        Map<String, SavedQuizSet> byId = new LinkedHashMap<>(); for (var set : sets) byId.put(set.id(), set);
        return new WrongAnswerNotebook().aggregate(sets, new QuizResultRepository.Listing(Arrays.stream(results)
                .map(result -> new QuizResultRepository.Validated(result, byId.get(result.quizSetId()))).toList(), 0, List.of()), 0, List.of());
    }
    public record FileState(String kind, String bytes, java.nio.file.attribute.FileTime modified) { }
    public static Map<String, FileState> snapshot(Path root) throws Exception {
        Map<String, FileState> state = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return state;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                boolean link = Files.isSymbolicLink(path), directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                state.put(root.relativize(path).toString(), new FileState(link ? "link" : directory ? "directory" : "file",
                        link ? Files.readSymbolicLink(path).toString() : directory ? "" : Base64.getEncoder().encodeToString(Files.readAllBytes(path)),
                        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)));
            }
        }
        return state;
    }
}
