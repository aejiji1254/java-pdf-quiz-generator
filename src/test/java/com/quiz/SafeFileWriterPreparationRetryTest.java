package com.quiz;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SafeFileWriterPreparationRetryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path target() { return temporary.getRoot().toPath().resolve("saved.json"); }
    private SafeFileWriter writer(SafeFileWriter.PreparationMover prepare, SafeFileWriter.Publisher publish) {
        return new SafeFileWriter(publish, (source, target) -> fail("최종 대체 게시가 호출되면 안 됩니다."),
                (temp, content) -> Files.write(temp, content), Files::deleteIfExists, prepare);
    }
    private void noTemps() throws IOException {
        try (var files = Files.list(temporary.getRoot().toPath())) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".quiz-")));
        }
    }

    @Test public void transientPreparationAccessDeniedRetriesThenPublishesCompleteBytes() throws Exception {
        AtomicInteger attempts = new AtomicInteger(), publishes = new AtomicInteger();
        SafeFileWriter writer = writer((source, ready, options) -> {
            assertArrayEquals(new CopyOption[] {StandardCopyOption.ATOMIC_MOVE}, options);
            if (attempts.incrementAndGet() < 3) throw new AccessDeniedException(source.toString(), ready.toString(), "temporary lock");
            Files.move(source, ready, options);
        }, (ready, target) -> { publishes.incrementAndGet(); Files.createLink(target, ready); });
        byte[] bytes = "완성한 내용".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(target(), writer.write(target(), bytes));
        assertEquals(3, attempts.get()); assertEquals(1, publishes.get());
        assertArrayEquals(bytes, Files.readAllBytes(target())); noTemps();
    }

    @Test public void exhaustedRetriesKeepOriginalFailureAndCleanBothOwnFiles() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AccessDeniedException denied = new AccessDeniedException("locked");
        SafeFileWriter writer = writer((source, ready, options) -> { attempts.incrementAndGet(); throw denied; },
                (ready, target) -> fail("실패한 준비 파일 게시 금지"));
        try { writer.write(target(), new byte[] {1}); fail("정책의 시도 횟수 이후 실패해야 합니다."); }
        catch (AccessDeniedException expected) {
            assertSame(denied, expected.getSuppressed()[0]);
            assertNull(expected.getCause());
            assertTrue(expected.getMessage().contains(SafeFileWriter.FILE_IN_USE_HINT));
            assertNotNull(expected.getFile()); assertNotNull(expected.getOtherFile());
            var message = com.quiz.gui.QuizMakerFrame.class.getDeclaredMethod("errorMessage", String.class, Throwable.class);
            message.setAccessible(true);
            assertTrue(((String) message.invoke(null, "저장 실패", new IOException("outer", expected)))
                    .contains(SafeFileWriter.FILE_IN_USE_HINT));
        }
        assertEquals(SafeFileWriter.DEFAULT_RETRY_POLICY.maxAttempts(), attempts.get()); assertFalse(Files.exists(target())); noTemps();
    }

    @Test public void otherPreparationErrorsAreNotRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        SafeFileWriter writer = writer((source, ready, options) -> { attempts.incrementAndGet(); throw new FileAlreadyExistsException(ready.toString()); },
                (ready, target) -> fail("게시 금지"));
        try { writer.write(target(), new byte[] {1}); fail("충돌 전달"); }
        catch (FileAlreadyExistsException expected) { }
        assertEquals(1, attempts.get()); assertFalse(Files.exists(target())); noTemps();
    }

    @Test public void finalTargetRaceDuringPreparationRetryDoesNotOverwriteOrRetryPublication() throws Exception {
        AtomicInteger attempts = new AtomicInteger(), publications = new AtomicInteger();
        SafeFileWriter writer = writer((source, ready, options) -> {
            if (attempts.incrementAndGet() == 1) {
                Files.writeString(target(), "다른 프로그램의 파일", StandardOpenOption.CREATE_NEW);
                throw new AccessDeniedException(source.toString());
            }
            Files.move(source, ready, options);
        }, (ready, target) -> { publications.incrementAndGet(); Files.createLink(target, ready); });
        try { writer.write(target(), new byte[] {1}); fail("기존 최종 파일 보호"); }
        catch (FileAlreadyExistsException expected) { }
        assertEquals("다른 프로그램의 파일", Files.readString(target()));
        assertEquals(2, attempts.get()); assertEquals(1, publications.get()); noTemps();
    }

    @Test public void interruptionAbortsRetryRestoresFlagAndCleansTemporaryFiles() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        SafeFileWriter writer = writer((source, ready, options) -> {
            attempts.incrementAndGet(); Thread.currentThread().interrupt(); throw new AccessDeniedException(source.toString());
        },
                (ready, target) -> fail("게시 금지"));
        try {
            writer.write(target(), new byte[] {1}); fail("인터럽트 중단");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(expected.getCause() instanceof InterruptedException);
        } finally { Thread.interrupted(); }
        assertEquals(1, attempts.get()); assertFalse(Files.exists(target())); noTemps();
    }

    @Test public void nonAtomicPreparationFallbackAlsoRetriesOnlyAccessDenied() throws Exception {
        AtomicInteger atomic = new AtomicInteger(), plain = new AtomicInteger();
        SafeFileWriter writer = writer((source, ready, options) -> {
            if (options.length > 0) { atomic.incrementAndGet(); throw new AtomicMoveNotSupportedException(source.toString(), ready.toString(), "unsupported"); }
            if (plain.incrementAndGet() == 1) throw new AccessDeniedException(source.toString());
            Files.move(source, ready, options);
        }, (ready, target) -> Files.createLink(target, ready));
        writer.write(target(), new byte[] {4, 5});
        assertEquals(1, atomic.get()); assertEquals(2, plain.get());
        assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(target())); noTemps();
    }

    @Test public void policyEmitsExactlyOneSingleLineLogForEachRetryAndNoLogWithoutRetry() throws Exception {
        java.util.List<String> log = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        SafeFileWriter writer = new SafeFileWriter((source, target) -> Files.createLink(target, source),
                (source, target) -> fail("대체 게시 금지"), (temp, content) -> Files.write(temp, content), Files::deleteIfExists,
                (source, ready, options) -> {
                    if (calls.incrementAndGet() <= 2) throw new AccessDeniedException(source.toString());
                    Files.move(source, ready, options);
                }, new SafeFileWriter.RetryPolicy(4), log::add);
        writer.write(target(), new byte[] {1});
        assertEquals(2, log.size());
        assertTrue(log.get(0).contains("2/4, 대기 20ms"));
        assertTrue(log.get(1).contains("3/4, 대기 40ms"));
        assertTrue(log.stream().noneMatch(line -> line.contains("\n") || line.contains("\r")));
        writer.write(target().resolveSibling("second.json"), new byte[] {2});
        assertEquals(2, log.size()); noTemps();
    }

    @Test public void singleAttemptPolicyAndInvalidPoliciesDoNotChangePublicationRules() throws Exception {
        AtomicInteger calls = new AtomicInteger(); java.util.List<String> log = new java.util.ArrayList<>();
        SafeFileWriter writer = new SafeFileWriter((source, target) -> fail("게시 금지"),
                (source, target) -> fail("게시 금지"), (temp, content) -> Files.write(temp, content), Files::deleteIfExists,
                (source, ready, options) -> { calls.incrementAndGet(); throw new AccessDeniedException(source.toString()); },
                new SafeFileWriter.RetryPolicy(1), log::add);
        try { writer.write(target(), new byte[] {1}); fail("단일 시도 실패 전달"); }
        catch (AccessDeniedException expected) { assertTrue(expected.getMessage().contains(SafeFileWriter.FILE_IN_USE_HINT)); }
        assertEquals(1, calls.get()); assertTrue(log.isEmpty()); noTemps();
        for (int limit : new int[] {0, 6}) try { new SafeFileWriter.RetryPolicy(limit); fail("시도 범위 검증"); }
        catch (IllegalArgumentException expected) { }
    }
}
