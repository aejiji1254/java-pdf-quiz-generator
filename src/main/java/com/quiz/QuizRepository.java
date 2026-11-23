package com.quiz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.nio.file.LinkOption;
import java.util.Comparator;

/** 프로젝트 내부의 JSON 문제 세트를 저장하고 검증해 불러온다. */
public class QuizRepository {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final Path directory;
    private final SafeFileWriter writer;

    public QuizRepository() {
        this(Path.of("data", "quizzes"));
    }

    public QuizRepository(Path directory) {
        this(directory, new SafeFileWriter());
    }

    QuizRepository(Path directory, SafeFileWriter writer) {
        this.directory = directory.toAbsolutePath().normalize();
        this.writer = writer;
    }

    Path directory() {
        return directory;
    }

    public record Entry(Path path, SavedQuizSet set) { }
    public record Listing(List<Entry> entries, int unreadableCount, List<String> reasons) {
        public Listing { entries = List.copyOf(entries); reasons = List.copyOf(reasons); }
    }

    /** 바로 아래 일반 JSON 파일만 조회하며, 각 파일은 load와 같은 엄격한 검증을 통과해야 한다. */
    public Listing list() throws IOException {
        if (!Files.exists(directory)) return new Listing(List.of(), 0, List.of());
        List<Entry> entries = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int unreadable = 0;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                    && p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try { entries.add(new Entry(path, load(path.toString()))); }
                catch (IOException e) {
                    unreadable++;
                    if (reasons.size() < 5) reasons.add(path.getFileName() + ": " + e.getMessage());
                }
            }
        }
        entries.sort(Comparator.comparing((Entry e) -> e.set().createdAt()).reversed()
                .thenComparing(e -> e.set().id()));
        return new Listing(entries, unreadable, reasons);
    }

    public Path save(SavedQuizSet set) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve(set.id() + ".json");
        try {
            writer.write(path, QuizJson.write(toJson(set)).getBytes(StandardCharsets.UTF_8));
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new IOException("같은 이름의 문제 세트가 이미 있습니다: " + path, e);
        }
        return path;
    }

    public SavedQuizSet load(String filename) throws IOException {
        if (filename == null || filename.isBlank()) {
            throw new IOException("불러올 JSON 파일 이름을 입력하세요.");
        }
        Path path;
        try {
            path = Path.of(filename.strip());
        } catch (java.nio.file.InvalidPathException e) {
            throw new IOException("파일 경로가 올바르지 않습니다.", e);
        }
        if (!path.isAbsolute()) path = directory.resolve(path);
        path = path.toAbsolutePath().normalize();
        if (!directory.equals(path.getParent()) || !path.toString().endsWith(".json")) {
            throw new IOException("data/quizzes 폴더의 JSON 파일만 불러올 수 있습니다.");
        }
        SavedQuizSet set = readSet(path);
        if (!path.getFileName().toString().equals(set.id() + ".json"))
            throw new IOException("파일 이름과 문제 세트 ID가 다릅니다.");
        return set;
    }

    /** 외부 파일에 한해서 라이브러리 위치·파일명과 ID 일치 검사만 생략한다. */
    public SavedQuizSet loadExternal(Path file) throws IOException {
        if (file == null || !file.toString().endsWith(".json")) throw new IOException("JSON(.json) 파일을 선택하세요.");
        return readSet(file.toAbsolutePath().normalize());
    }

    Path exportFile(SavedQuizSet set, Path target) throws IOException {
        if (target == null || !target.toString().endsWith(".json")) throw new IOException("내보낼 파일은 .json이어야 합니다.");
        return writer.write(target, QuizJson.write(toJson(set)).getBytes(StandardCharsets.UTF_8));
    }

    boolean containsSetId(String id) { return Files.exists(directory.resolve(id + ".json"), LinkOption.NOFOLLOW_LINKS); }

    private static SavedQuizSet readSet(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("저장된 파일을 찾을 수 없습니다: " + path);
        try {
            if (Files.size(path) > MAX_BYTES) throw new IOException("JSON 파일이 너무 큽니다.");
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("JSON 파일이 너무 큽니다.");
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            SavedQuizSet set = fromJson(QuizJson.parse(json), Files.getLastModifiedTime(path).toInstant());
            return set;
        } catch (IllegalArgumentException | NullPointerException | java.time.DateTimeException e) {
            throw new IOException("문제 세트 JSON이 올바르지 않습니다: " + e.getMessage(), e);
        } catch (java.nio.charset.MalformedInputException e) {
            throw new IOException("UTF-8 JSON 파일이 아닙니다.", e);
        }
    }

    private static Map<String, Object> toJson(SavedQuizSet set) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 2);
        root.put("id", set.id());
        root.put("sourcePdf", set.sourcePdf());
        root.put("title", set.title());
        root.put("subject", set.subject());
        root.put("createdAt", set.createdAt().toString());
        root.put("derivedFromSetId", set.derivedFromSetId());
        List<Object> items = new ArrayList<>();
        for (SavedQuestion question : set.questions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", question.id());
            item.put("type", question.type().name());
            item.put("question", question.question());
            item.put("options", question.options());
            item.put("correctIndex", question.correctIndex());
            item.put("explanation", question.explanation());
            item.put("sourcePdf", question.sourcePdf());
            item.put("page", question.page());
            item.put("lineNumber", question.lineNumber());
            item.put("origin", question.origin().name());
            item.put("derivedFromQuestionId", question.derivedFromQuestionId());
            items.add(item);
        }
        root.put("questions", items);
        return root;
    }

    private static SavedQuizSet fromJson(Object value, Instant modifiedAt) {
        if (!(value instanceof Map<?, ?> rawRoot) || !(rawRoot.get("version") instanceof Integer version)
                || version != 1 && version != 2) {
            throw new IllegalArgumentException("지원하지 않는 JSON 버전입니다.");
        }
        Map<String, Object> root = object(value, version == 1
                ? Set.of("version", "id", "sourcePdf", "questions")
                : Set.of("version", "id", "sourcePdf", "questions", "title", "subject", "createdAt", "derivedFromSetId"));
        Object rawQuestions = root.get("questions");
        if (!(rawQuestions instanceof List<?> list)) {
            throw new IllegalArgumentException("questions 배열이 필요합니다.");
        }
        List<SavedQuestion> questions = new ArrayList<>();
        for (Object raw : list) {
            Map<String, Object> item = object(raw, version == 1
                    ? Set.of("id", "type", "question", "options", "correctIndex", "explanation", "sourcePdf", "page", "lineNumber")
                    : Set.of("id", "type", "question", "options", "correctIndex", "explanation", "sourcePdf", "page", "lineNumber", "origin", "derivedFromQuestionId"));
            Object rawOptions = item.get("options");
            if (!(rawOptions instanceof List<?> values)) {
                throw new IllegalArgumentException("options 배열이 필요합니다.");
            }
            List<String> options = new ArrayList<>();
            for (Object option : values) options.add(string(option, "option"));
            SavedQuestion.Type type;
            try {
                type = SavedQuestion.Type.valueOf(string(item.get("type"), "type"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("문제 유형이 올바르지 않습니다.", e);
            }
            String explanation = string(item.get("explanation"), "explanation");
            SavedQuestion.Origin origin = version == 1
                    ? explanation.startsWith("[직접 작성] ") ? SavedQuestion.Origin.MANUAL : SavedQuestion.Origin.UNKNOWN
                    : SavedQuestion.Origin.valueOf(string(item.get("origin"), "origin"));
            questions.add(new SavedQuestion(string(item.get("id"), "id"), type,
                    string(item.get("question"), "question"), options,
                    integer(item.get("correctIndex"), "correctIndex"),
                    explanation,
                    string(item.get("sourcePdf"), "sourcePdf"),
                    optionalInteger(item.get("page"), "page"),
                    optionalInteger(item.get("lineNumber"), "lineNumber"), origin,
                    version == 1 ? null : optionalString(item.get("derivedFromQuestionId"), "derivedFromQuestionId")));
        }
        String source = string(root.get("sourcePdf"), "sourcePdf");
        return new SavedQuizSet(string(root.get("id"), "id"), source, questions,
                version == 1 ? QuizMetadata.legacyTitle(source) : string(root.get("title"), "title"),
                version == 1 ? "" : string(root.get("subject"), "subject"),
                version == 1 ? modifiedAt : Instant.parse(string(root.get("createdAt"), "createdAt")),
                version == 1 ? null : optionalString(root.get("derivedFromSetId"), "derivedFromSetId"));
    }

    private static Map<String, Object> object(Object value, Set<String> fields) {
        if (!(value instanceof Map<?, ?> raw) || !raw.keySet().equals(fields)) {
            throw new IllegalArgumentException("JSON 객체의 필수 필드 또는 구조가 올바르지 않습니다.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) result.put(field, raw.get(field));
        return result;
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String result)) throw new IllegalArgumentException(field + " 문자열이 필요합니다.");
        return result;
    }

    private static String optionalString(Object value, String field) {
        return value == null ? null : string(value, field);
    }

    private static int integer(Object value, String field) {
        if (!(value instanceof Integer result)) throw new IllegalArgumentException(field + " 정수가 필요합니다.");
        return result;
    }

    private static Integer optionalInteger(Object value, String field) {
        return value == null ? null : integer(value, field);
    }
}
