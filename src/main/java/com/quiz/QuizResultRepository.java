package com.quiz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 학습 결과를 독립적인 UTF-8 JSON 파일로 저장한다. */
public class QuizResultRepository {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final Path directory;

    public QuizResultRepository() {
        this(Path.of("data", "results"));
    }

    public QuizResultRepository(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path save(QuizResult result, SavedQuizSet set) throws IOException {
        result.validateAgainst(set);
        Files.createDirectories(directory);
        Path path = directory.resolve(result.id() + ".json");
        try {
            Files.writeString(path, QuizJson.write(toJson(result)), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new IOException("같은 이름의 학습 결과가 이미 있습니다: " + path, e);
        }
        return path;
    }

    public QuizResult load(String filename, SavedQuizSet set) throws IOException {
        if (filename == null || filename.isBlank())
            throw new IOException("불러올 학습 결과 JSON 파일 이름을 입력하세요.");
        Path path;
        try {
            path = Path.of(filename.strip());
        } catch (java.nio.file.InvalidPathException e) {
            throw new IOException("파일 경로가 올바르지 않습니다.", e);
        }
        if (!path.isAbsolute()) path = directory.resolve(path);
        path = path.toAbsolutePath().normalize();
        if (!directory.equals(path.getParent()) || !path.toString().endsWith(".json"))
            throw new IOException("data/results 폴더의 JSON 파일만 불러올 수 있습니다.");
        if (!Files.isRegularFile(path)) throw new IOException("학습 결과 파일을 찾을 수 없습니다: " + path);
        try {
            if (Files.size(path) > MAX_BYTES) throw new IOException("학습 결과 JSON 파일이 너무 큽니다.");
            QuizResult result = fromJson(QuizJson.parse(Files.readString(path, StandardCharsets.UTF_8)));
            if (!path.getFileName().toString().equals(result.id() + ".json"))
                throw new IOException("파일 이름과 학습 결과 ID가 다릅니다.");
            result.validateAgainst(set);
            return result;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("학습 결과 JSON이 올바르지 않습니다: " + e.getMessage(), e);
        } catch (java.nio.charset.MalformedInputException e) {
            throw new IOException("UTF-8 JSON 파일이 아닙니다.", e);
        }
    }

    public record Validated(QuizResult result, SavedQuizSet set) { }
    public record Listing(List<Validated> entries, int excludedCount, List<String> reasons) {
        public Listing { entries = List.copyOf(entries); reasons = List.copyOf(reasons); }
    }

    /** 조회는 폴더 생성/저장 없이 수행한다. 세트 맵은 QuizRepository.list()에서 한 번 읽은 캐시다. */
    public Listing list(Map<String, SavedQuizSet> sets) throws IOException {
        if (!Files.exists(directory)) return new Listing(List.of(), 0, List.of());
        List<Validated> entries = new ArrayList<>(); List<String> reasons = new ArrayList<>(); int excluded = 0;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    if (Files.size(path) > MAX_BYTES) throw new IOException("학습 결과 JSON 파일이 너무 큽니다.");
                    byte[] bytes;
                    try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
                    if (bytes.length > MAX_BYTES) throw new IOException("학습 결과 JSON 파일이 너무 큽니다.");
                    String json = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                    QuizResult result = fromJson(QuizJson.parse(json));
                    if (!path.getFileName().toString().equals(result.id() + ".json"))
                        throw new IOException("파일 이름과 학습 결과 ID가 다릅니다.");
                    SavedQuizSet set = sets.get(result.quizSetId());
                    if (set == null) throw new IOException("원본 문제 세트가 없거나 읽을 수 없습니다: " + result.quizSetId());
                    result.validateAgainst(set);
                    entries.add(new Validated(result, set));
                } catch (IOException | IllegalArgumentException | NullPointerException e) {
                    excluded++;
                    if (reasons.size() < 5) reasons.add(path.getFileName() + ": "
                            + (e instanceof java.nio.charset.CharacterCodingException ? "UTF-8 JSON 파일이 아닙니다." : e.getMessage()));
                }
            }
        }
        return new Listing(entries, excluded, reasons);
    }

    private static Map<String, Object> toJson(QuizResult result) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("id", result.id());
        root.put("quizSetId", result.quizSetId());
        root.put("sourcePdf", result.sourcePdf());
        root.put("reviewOfResultId", result.reviewOfResultId());
        root.put("totalQuestions", result.totalQuestions());
        root.put("createdAt", result.createdAt().toString());
        List<Object> answers = new ArrayList<>();
        for (AnswerRecord answer : result.answers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionId", answer.questionId());
            item.put("userAnswer", answer.userAnswer());
            item.put("correct", answer.correct());
            item.put("answeredAt", answer.answeredAt().toString());
            answers.add(item);
        }
        root.put("answers", answers);
        if (!result.attemptCounts().isEmpty()) {
            root.put("attemptCounts", result.attemptCounts());
            root.put("solvedQuestionIds", new ArrayList<>(result.solvedQuestionIds()));
        }
        return root;
    }

    private static QuizResult fromJson(Object value) {
        Set<String> baseFields = Set.of("version", "id", "quizSetId", "sourcePdf",
                "reviewOfResultId", "totalQuestions", "createdAt", "answers");
        Set<String> extendedFields = Set.of("version", "id", "quizSetId", "sourcePdf",
                "reviewOfResultId", "totalQuestions", "createdAt", "answers",
                "attemptCounts", "solvedQuestionIds");
        if (!(value instanceof Map<?, ?> rawRoot)
                || !rawRoot.keySet().equals(baseFields) && !rawRoot.keySet().equals(extendedFields))
            throw new IllegalArgumentException("JSON 객체의 필수 필드 또는 구조가 올바르지 않습니다.");
        Map<String, Object> root = new LinkedHashMap<>();
        for (Object key : rawRoot.keySet()) root.put((String) key, rawRoot.get(key));
        if (!Integer.valueOf(1).equals(root.get("version")))
            throw new IllegalArgumentException("지원하지 않는 결과 JSON 버전입니다.");
        if (!(root.get("answers") instanceof List<?> rawAnswers))
            throw new IllegalArgumentException("answers 배열이 필요합니다.");
        List<AnswerRecord> answers = new ArrayList<>();
        for (Object raw : rawAnswers) {
            Map<String, Object> item = object(raw,
                    Set.of("questionId", "userAnswer", "correct", "answeredAt"));
            if (!(item.get("correct") instanceof Boolean correct))
                throw new IllegalArgumentException("correct 참/거짓 값이 필요합니다.");
            answers.add(new AnswerRecord(string(item.get("questionId"), "questionId"),
                    string(item.get("userAnswer"), "userAnswer"), correct,
                    instant(item.get("answeredAt"), "answeredAt")));
        }
        Object parent = root.get("reviewOfResultId");
        Map<String, Integer> attempts = new LinkedHashMap<>();
        Set<String> solved = new java.util.HashSet<>();
        if (root.containsKey("attemptCounts")) {
            if (!(root.get("attemptCounts") instanceof Map<?, ?> rawAttempts)
                    || !(root.get("solvedQuestionIds") instanceof List<?> rawSolved))
                throw new IllegalArgumentException("재시도 상태의 구조가 올바르지 않습니다.");
            for (Map.Entry<?, ?> entry : rawAttempts.entrySet())
                attempts.put(string(entry.getKey(), "questionId"), integer(entry.getValue(), "attempts"));
            for (Object id : rawSolved)
                if (!solved.add(string(id, "questionId")))
                    throw new IllegalArgumentException("해결된 문제 ID가 중복되었습니다.");
        }
        return new QuizResult(string(root.get("id"), "id"), string(root.get("quizSetId"), "quizSetId"),
                string(root.get("sourcePdf"), "sourcePdf"),
                parent == null ? null : string(parent, "reviewOfResultId"),
                integer(root.get("totalQuestions"), "totalQuestions"), instant(root.get("createdAt"), "createdAt"),
                answers, attempts, solved);
    }

    private static Map<String, Object> object(Object value, Set<String> fields) {
        if (!(value instanceof Map<?, ?> raw) || !raw.keySet().equals(fields))
            throw new IllegalArgumentException("JSON 객체의 필수 필드 또는 구조가 올바르지 않습니다.");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) result.put(field, raw.get(field));
        return result;
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String text))
            throw new IllegalArgumentException(field + " 문자열이 필요합니다.");
        return text;
    }

    private static int integer(Object value, String field) {
        if (!(value instanceof Integer number))
            throw new IllegalArgumentException(field + " 정수가 필요합니다.");
        return number;
    }

    private static Instant instant(Object value, String field) {
        try {
            return Instant.parse(string(value, field));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 시각이 올바르지 않습니다.", e);
        }
    }
}
