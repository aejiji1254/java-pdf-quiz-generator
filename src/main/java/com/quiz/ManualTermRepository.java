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

/** 기존 QuizJson을 사용하는 별도의 수동 입력 JSON 저장소. */
public final class ManualTermRepository {
    private final Path directory;

    public ManualTermRepository() { this(Path.of("data", "manual-terms")); }
    public ManualTermRepository(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    public Path save(ManualTermSet set) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(set.id() + ".json");
        List<Object> terms = new ArrayList<>();
        for (Term term : set.terms()) {
            terms.add(Map.of("term", term.term(), "description", term.description()));
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", 1);
        json.put("id", set.id());
        json.put("associatedPdf", set.associatedPdf());
        json.put("terms", terms);
        Files.writeString(file, QuizJson.write(json), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return file;
    }

    public ManualTermSet load(Path file) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        if (!directory.equals(target.getParent()) || !target.toString().endsWith(".json"))
            throw new IOException("수동 용어 폴더의 JSON 파일만 불러올 수 있습니다.");
        if (!Files.isRegularFile(target)) throw new IOException("수동 용어 파일이 없습니다: " + target);
        if (Files.size(target) > 1024 * 1024) throw new IOException("수동 용어 JSON 파일이 너무 큽니다.");
        try {
            Object parsed = QuizJson.parse(Files.readString(target, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root)
                    || !root.keySet().equals(Set.of("version", "id", "associatedPdf", "terms"))
                    || !Integer.valueOf(1).equals(root.get("version"))
                    || !(root.get("id") instanceof String id)
                    || !(root.get("associatedPdf") instanceof String source)
                    || !(root.get("terms") instanceof List<?> rows))
                throw new IllegalArgumentException("필수 필드가 없거나 형식이 잘못되었습니다.");
            List<Term> terms = new ArrayList<>();
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> item)
                        || !item.keySet().equals(Set.of("term", "description"))
                        || !(item.get("term") instanceof String term)
                        || !(item.get("description") instanceof String description))
                    throw new IllegalArgumentException("용어 항목의 형식이 잘못되었습니다.");
                terms.add(new Term(term, description, 0));
            }
            ManualTermSet set = new ManualTermSet(id, source, terms);
            if (!target.getFileName().toString().equals(set.id() + ".json"))
                throw new IllegalArgumentException("파일명과 세트 ID가 다릅니다.");
            return set;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("수동 용어 JSON이 잘못되었습니다: " + e.getMessage(), e);
        }
    }
}
