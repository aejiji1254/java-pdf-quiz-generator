package com.quiz;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** 일반 Surefire *Test 검색 대상 아님. mvn test -Dtest=SafeFileWriterStress -Dquiz.stress=true 로만 반복 실행. */
public class SafeFileWriterStress {
    private record Result(int attempts, int writes, int failures, int exhausted, int otherFailures,
            int retries, int maxRetries, int residue) {
        String row() {
            return String.format(Locale.ROOT,
                    "attempts=%d writes=%d failures=%d exhausted=%d otherFailures=%d retries=%d averageRetries=%.6f maxRetries=%d residue=%d",
                    attempts, writes, failures, exhausted, otherFailures, retries, (double) retries / writes, maxRetries, residue);
        }
    }

    @Test public void compareRealFileWritesWithSingleAttemptAndCurrentPolicy() throws Exception {
        assumeTrue("반복 측정은 -Dquiz.stress=true가 필요합니다.", Boolean.getBoolean("quiz.stress"));
        int writes = 1000;
        Path root = Files.createTempDirectory(Path.of("target"), "safe-writer-stress-");
        List<Result> results = new ArrayList<>();
        results.add(measure(root.resolve("single"), writes, new SafeFileWriter.RetryPolicy(1)));
        Result current = measure(root.resolve("current"), writes, SafeFileWriter.DEFAULT_RETRY_POLICY);
        results.add(current);
        if (current.exhausted() > 0 && current.attempts() == 4)
            results.add(measure(root.resolve("five"), writes, new SafeFileWriter.RetryPolicy(5)));
        String report = "os=" + System.getProperty("os.name") + " java=" + System.getProperty("java.version")
                + "\n평균 재시도 횟수 = 실제 재시도 수 / 전체 저장 시도 수 (초기 시도 제외)\n"
                + String.join("\n", results.stream().map(Result::row).toList()) + "\n";
        Path output = root.resolve("summary.txt"); Files.writeString(output, report);
        System.out.print("STRESS_REPORT " + output.toAbsolutePath() + "\n" + report);
        for (Result result : results) { assertEquals("자체 임시 파일 잔여 없음", 0, result.residue()); assertEquals(writes, result.writes()); }
        Result effective = results.getLast();
        assertEquals("현재/증가 정책의 소진 실패: " + output, 0, effective.exhausted());
        assertEquals("게시 등 다른 실패를 숨기지 않는다: " + output, 0, effective.otherFailures());
    }

    private Result measure(Path directory, int writes, SafeFileWriter.RetryPolicy policy) throws Exception {
        Files.createDirectories(directory);
        AtomicInteger calls = new AtomicInteger(), denied = new AtomicInteger(), logCount = new AtomicInteger();
        SafeFileWriter writer = new SafeFileWriter((ready, target) -> Files.createLink(target, ready),
                (ready, target) -> Files.move(ready, target),
                (temp, content) -> Files.write(temp, content, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                Files::deleteIfExists,
                (source, ready, options) -> {
                    calls.incrementAndGet();
                    try { Files.move(source, ready, options); }
                    catch (AccessDeniedException error) { denied.incrementAndGet(); throw error; }
                }, policy, line -> { logCount.incrementAndGet(); System.err.println(line); });
        int failures = 0, exhausted = 0, other = 0, retries = 0, maxRetries = 0;
        byte[] bytes = "{\"complete\":true,\"text\":\"완성된 UTF-8 내용\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < writes; i++) {
            calls.set(0); denied.set(0);
            Path target = directory.resolve(i + ".json");
            try { writer.write(target, bytes); assertArrayEquals(bytes, Files.readAllBytes(target)); }
            catch (IOException error) {
                failures++;
                if (error instanceof AccessDeniedException && calls.get() == policy.maxAttempts() && denied.get() == calls.get()) exhausted++;
                else other++;
                assertFalse("실패한 저장이 반쪽 파일을 게시하면 안 된다", Files.exists(target));
            }
            int repeated = Math.max(0, calls.get() - 1); retries += repeated; maxRetries = Math.max(maxRetries, repeated);
        }
        assertEquals("각 재시도에 한 줄 로그", retries, logCount.get());
        int residue;
        try (var files = Files.list(directory)) { residue = (int) files.filter(p -> p.getFileName().toString().startsWith(".quiz-")).count(); }
        return new Result(policy.maxAttempts(), writes, failures, exhausted, other, retries, maxRetries, residue);
    }
}
