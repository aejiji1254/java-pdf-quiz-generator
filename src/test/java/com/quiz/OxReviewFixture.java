package com.quiz;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 변경 전 기준 캡처용 일회성 도구. 제품 코드에는 포함되지 않는다. */
public final class OxReviewFixture {
    static final Path BASELINE = Path.of("src/test/resources/baseline/ox-seed7-before.json");
    record Sample(Path pdf, int first, int last, TermExtractor.Result extracted, String error) {
        String key() { return pdf.toString().replace('\\', '/') + "#" + first + "-" + last; }
    }

    static List<Sample> samples() throws IOException {
        List<Path> pdfs = new ArrayList<>(List.of(Path.of("sample.pdf")));
        try (var files = Files.list(Path.of("samples"))) {
            pdfs.addAll(files.filter(Files::isRegularFile).filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted(Comparator.comparing(Path::toString)).toList());
        }
        List<Sample> samples = new ArrayList<>();
        PdfReader reader = new PdfReader();
        for (Path pdf : pdfs) {
            int total = reader.pageCount(pdf);
            for (int first = 1; first <= total; first += 50) {
                int last = Math.min(first + 49, total);
                try { samples.add(new Sample(pdf, first, last,
                        new TermExtractor().extract(reader.readRange(pdf, first, last), first), null)); }
                catch (IOException e) { samples.add(new Sample(pdf, first, last, null, e.getMessage())); }
            }
        }
        return samples;
    }

    static QuizGenerator.OxResult generate(Sample sample) {
        QuizGenerator generator = new QuizGenerator(new Random(7));
        generator.generateMultipleChoice(sample.extracted()); // GUI의 호출 순서를 고정한다.
        return generator.generateOxDetailed(sample.extracted());
    }

    public static void main(String[] args) throws Exception {
        if (Files.exists(BASELINE)) throw new IOException("고정 baseline은 덮어쓸 수 없습니다: " + BASELINE);
        List<Object> documents = new ArrayList<>();
        for (Sample sample : samples()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("key", sample.key());
            doc.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sample.pdf()))));
            doc.put("error", sample.error());
            List<Object> questions = new ArrayList<>();
            if (sample.error() == null) {
                QuizGenerator.OxResult result = generate(sample);
                SavedQuizSet saved = SavedQuizSet.fromGenerated(sample.pdf().toString(), sample.extracted(), result.questions(), List.of());
                doc.put("candidateCount", result.candidateCount());
                doc.put("summary", result.summary());
                doc.put("skipped", result.skipped());
                for (int i = 0; i < result.questions().size(); i++) {
                    QuizGenerator.OxQuestion q = result.questions().get(i);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("anchor", q.original().term()); row.put("anchorPage", q.original().page());
                    row.put("anchorLine", saved.questions().get(i).lineNumber());
                    row.put("statement", q.statement()); row.put("presentedDescription", q.description());
                    row.put("sourceTerm", q.original().term()); row.put("sourcePage", q.original().page());
                    row.put("answer", q.answer()); row.put("explanation", saved.questions().get(i).explanation());
                    questions.add(row);
                }
            }
            doc.put("questions", questions); documents.add(doc);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("seed", 7); root.put("callOrder", "multipleChoice -> ox"); root.put("documents", documents);
        Files.createDirectories(BASELINE.getParent());
        Files.writeString(BASELINE, QuizJson.write(root), StandardOpenOption.CREATE_NEW);
        System.out.println("Captured " + documents.size() + " PDF ranges: " + BASELINE);
    }
}
