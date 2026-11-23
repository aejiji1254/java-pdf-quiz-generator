package com.quiz;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Test;

/** 일회성 외부 폴더 평가. 일반 테스트 검색 대상 아님. 프로퍼티가 없으면 파일/콘솔에 아무 작업도 하지 않는다. */
public class StructuredExtractionEvaluation {
    @Test public void evaluateIfRequested() throws Exception {
        String directory = System.getProperty("quiz.eval.dir");
        if (directory == null || directory.isBlank()) return;
        String report;
        try { report = evaluate(Path.of(directory)); }
        catch (InvalidPathException e) { throw new IOException("평가 폴더 경로가 올바르지 않습니다."); }
        System.out.print(report);
        writeIfRequested(report, Path.of("docs/structured-evaluation.md"));
    }
    static Path checkedDirectory(Path directory, Path data) throws IOException {
        Path input, blocked;
        try {
            input = directory.toRealPath();
            blocked = Files.exists(data) ? data.toRealPath() : data.toAbsolutePath().normalize();
        } catch (IOException e) { throw new IOException("평가 폴더를 읽을 수 없습니다."); }
        if (!Files.isDirectory(input)) throw new IOException("평가 대상은 폴더여야 합니다.");
        if (input.startsWith(blocked)) throw new IOException("평가 폴더는 프로젝트 data/ 밖이어야 합니다.");
        return input;
    }
    static String evaluate(Path directory) throws Exception {
        Path input = checkedDirectory(directory, Path.of("data"));
        List<Path> files;
        try (var paths = Files.walk(input)) { files = paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).sorted().toList(); }
        catch (IOException e) { throw new IOException("평가 폴더의 파일 목록을 읽을 수 없습니다."); }
        List<StructuredPdfMeasurements.Document> docs = new ArrayList<>(); List<String> failures = new ArrayList<>();
        for (Path file : files) {
            String prefix = "해시 확인 불가";
            try {
                prefix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))).substring(0, 12);
                docs.add(StructuredPdfMeasurements.read(file));
            } catch (IOException | IllegalArgumentException e) {
                // IOException 원문에는 전체 경로가 포함될 수 있어 종류만 출력한다.
                failures.add(file.getFileName() + " [" + prefix + "]: 처리 불가 (" + e.getClass().getSimpleName() + ")");
            }
        }
        StringBuilder report = new StringBuilder(StructuredPdfMeasurements.markdown(docs));
        report.append("\n## 처리 안내\n\nPDF ").append(files.size()).append("개, 성공 ").append(docs.size()).append("개, 실패 ").append(failures.size()).append("개\n");
        for (String failure : failures) report.append("\n- ").append(failure).append("\n");
        return report.toString();
    }
    static void writeIfRequested(String report, Path output) throws IOException {
        if (Boolean.getBoolean("quiz.writeDocs")) Files.writeString(output, report);
    }
}
