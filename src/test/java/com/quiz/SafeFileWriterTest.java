package com.quiz;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SafeFileWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root() { return temporary.getRoot().toPath(); }
    private Path target() { return root().resolve("output.json"); }
    private SafeFileWriter writer(SafeFileWriter.Publisher link, SafeFileWriter.Publisher move) {
        return new SafeFileWriter(link, move, (temp, bytes) -> Files.write(temp, bytes), Files::deleteIfExists);
    }
    private void noTemporaryFiles() throws IOException {
        try (var files = Files.list(root())) { assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".quiz-"))); }
    }

    @Test public void unsupportedLinkFallsBackForBothExceptionKindsWithoutReplacingAnything() throws Exception {
        for (boolean io : new boolean[] {false, true}) {
            AtomicInteger moves = new AtomicInteger();
            SafeFileWriter writer = writer((temp, target) -> {
                if (io) throw new FileSystemException(temp.toString(), target.toString(), "Operation not supported");
                throw new UnsupportedOperationException("hard links unsupported");
            }, (temp, target) -> { moves.incrementAndGet(); Files.move(temp, target); });
            Path target = root().resolve(io + ".json");
            assertEquals(target, writer.write(target, "complete".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals("complete", Files.readString(target));
            assertEquals(1, moves.get());
            noTemporaryFiles();
        }
    }

    @Test public void targetRaceDuringLinkMustNotFallBackAndLeavesExistingBytesUntouched() throws Exception {
        AtomicInteger moves = new AtomicInteger();
        SafeFileWriter writer = writer((temp, target) -> {
            Files.writeString(target, "other program", StandardOpenOption.CREATE_NEW);
            throw new FileAlreadyExistsException(target.toString());
        }, (temp, target) -> moves.incrementAndGet());
        try { writer.write(target(), new byte[] {1}); fail("대상 경쟁 생성 거부"); }
        catch (FileAlreadyExistsException expected) { assertEquals("other program", Files.readString(target())); }
        assertEquals(0, moves.get());
        noTemporaryFiles();
    }

    @Test public void targetRaceDuringFallbackMoveDoesNotReplaceAndRemovesReadyFile() throws Exception {
        SafeFileWriter writer = writer((temp, target) -> { throw new UnsupportedOperationException(); }, (temp, target) -> {
            Files.writeString(target, "raced file", StandardOpenOption.CREATE_NEW);
            Files.move(temp, target);
        });
        try { writer.write(target(), new byte[] {1}); fail("이동 대상 경쟁 생성 거부"); }
        catch (FileAlreadyExistsException expected) { assertEquals("raced file", Files.readString(target())); }
        noTemporaryFiles();
    }

    @Test public void anUnsupportedMessageWithExistingTargetIsAConflictNotFallback() throws Exception {
        AtomicInteger moves = new AtomicInteger();
        SafeFileWriter writer = writer((temp, target) -> {
            Files.writeString(target, "existing", StandardOpenOption.CREATE_NEW);
            throw new FileSystemException(temp.toString(), target.toString(), "Operation not supported");
        }, (temp, target) -> moves.incrementAndGet());
        try { writer.write(target(), new byte[] {1}); fail("기존 대상 거부"); }
        catch (FileAlreadyExistsException expected) { assertEquals("existing", Files.readString(target())); }
        assertEquals(0, moves.get()); noTemporaryFiles();
    }

    @Test public void unsupportedOperationWithAnExistingTargetDoesNotFallBackEither() throws Exception {
        SafeFileWriter writer = writer((temp, target) -> {
            Files.writeString(target, "existing", StandardOpenOption.CREATE_NEW);
            throw new UnsupportedOperationException();
        }, (temp, target) -> fail("대상이 생긴 뒤에는 대체 이동하지 않아야 합니다."));
        try { writer.write(target(), new byte[] {1}); fail("이미 존재하는 대상 거부"); }
        catch (FileAlreadyExistsException expected) { assertEquals("existing", Files.readString(target())); }
        noTemporaryFiles();
    }

    @Test public void accessDeniedDoesNotFallBackAndReadyFilesAreCleaned() throws Exception {
        SafeFileWriter writer = writer((temp, target) -> { throw new AccessDeniedException(target.toString()); },
                (temp, target) -> fail("권한 오류를 미지원으로 처리하면 안 됩니다."));
        try { writer.write(target(), new byte[] {1}); fail("권한 오류 전달"); }
        catch (AccessDeniedException expected) { assertFalse(Files.exists(target())); }
        noTemporaryFiles();
    }

    @Test public void partialWriteNeverPublishesAndDoesNotDeleteAnotherProgramsTemp() throws Exception {
        Path foreign = root().resolve("other-program.tmp");
        Files.writeString(foreign, "keep");
        SafeFileWriter writer = new SafeFileWriter((temp, target) -> fail("불완전 파일 게시 금지"),
                (temp, target) -> fail("불완전 파일 이동 금지"), (temp, bytes) -> {
                    Files.writeString(temp, "partial"); throw new IOException("write failed");
                }, Files::deleteIfExists);
        try { writer.write(target(), new byte[] {1}); fail("쓰기 오류 전달"); }
        catch (IOException expected) { assertEquals("write failed", expected.getMessage()); }
        assertFalse(Files.exists(target())); assertEquals("keep", Files.readString(foreign)); noTemporaryFiles();
    }

    @Test public void cleanupFailureAfterSuccessfulPublishDoesNotFailSaveAndListingIgnoresResidue() throws Exception {
        SafeFileWriter writer = new SafeFileWriter((temp, target) -> Files.createLink(target, temp),
                (temp, target) -> Files.move(temp, target), (temp, bytes) -> Files.write(temp, bytes),
                temp -> { throw new IOException("cleanup denied"); });
        QuizRepository repository = new QuizRepository(root(), writer);
        SavedQuizSet set = new SavedQuizSet(java.util.UUID.randomUUID().toString(), "자료.pdf", java.util.List.of());
        Path saved = repository.save(set);
        assertEquals(set, repository.load(saved.toString()));
        try (var files = Files.list(root())) { assertTrue(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
        assertEquals(1, repository.list().entries().size());
        assertEquals(0, repository.list().unreadableCount());
    }

    @Test public void cleanupFailureDoesNotMaskOriginalWriteFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        SafeFileWriter writer = new SafeFileWriter((temp, target) -> fail("게시 금지"),
                (temp, target) -> fail("이동 금지"), (temp, bytes) -> { throw new IOException("original write error"); },
                temp -> { attempts.incrementAndGet(); throw new IOException("cleanup error"); });
        try { writer.write(target(), new byte[] {1}); fail("쓰기 오류 전달"); }
        catch (IOException expected) { assertEquals("original write error", expected.getMessage()); }
        assertEquals(1, attempts.get());
        assertFalse(Files.exists(target()));
        assertEquals(0, new QuizRepository(root()).list().unreadableCount());
    }
}
