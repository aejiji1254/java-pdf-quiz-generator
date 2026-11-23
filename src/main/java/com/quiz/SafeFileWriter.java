package com.quiz;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.function.Consumer;

/** 같은 폴더의 완성 파일만 게시하며 기존 최종 파일을 교체하지 않는다. */
final class SafeFileWriter {
    @FunctionalInterface interface Publisher { void publish(Path completed, Path target) throws IOException; }
    @FunctionalInterface interface ContentWriter { void write(Path temp, byte[] content) throws IOException; }
    @FunctionalInterface interface Cleaner { void delete(Path temp) throws IOException; }
    @FunctionalInterface interface PreparationMover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }
    record RetryPolicy(int maxAttempts) {
        RetryPolicy {
            if (maxAttempts < 1 || maxAttempts > 5) throw new IllegalArgumentException("파일 이동 시도는 1~5회입니다.");
        }
        long delayMillis(int retryIndex) { return 20L << retryIndex; }
    }
    static final RetryPolicy DEFAULT_RETRY_POLICY = new RetryPolicy(4);
    static final String FILE_IN_USE_HINT = "다른 프로그램(백신·클라우드 동기화 등)이 파일을 사용 중일 수 있습니다";

    private final Publisher link;
    private final Publisher move;
    private final ContentWriter writer;
    private final Cleaner cleaner;
    private final PreparationMover preparationMover;
    private final RetryPolicy retryPolicy;
    private final Consumer<String> retryLog;

    SafeFileWriter() {
        this((completed, target) -> Files.createLink(target, completed),
                (completed, target) -> Files.move(completed, target),
                (temp, content) -> Files.write(temp, content, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                Files::deleteIfExists);
    }

    SafeFileWriter(Publisher link, Publisher move, ContentWriter writer, Cleaner cleaner) {
        this(link, move, writer, cleaner, (source, target, options) -> Files.move(source, target, options));
    }

    SafeFileWriter(Publisher link, Publisher move, ContentWriter writer, Cleaner cleaner, PreparationMover preparationMover) {
        this(link, move, writer, cleaner, preparationMover, DEFAULT_RETRY_POLICY, message -> System.err.println(message));
    }

    SafeFileWriter(Publisher link, Publisher move, ContentWriter writer, Cleaner cleaner, PreparationMover preparationMover,
            RetryPolicy retryPolicy, Consumer<String> retryLog) {
        this.link = link; this.move = move; this.writer = writer; this.cleaner = cleaner;
        this.preparationMover = preparationMover;
        this.retryPolicy = retryPolicy; this.retryLog = retryLog;
    }

    Path write(Path destination, byte[] content) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("저장할 폴더가 없습니다.");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        Path temp = Files.createTempFile(parent, ".quiz-write-", ".tmp");
        Path ready = null;
        try {
            writer.write(temp, content);
            ready = Files.createTempFile(parent, ".quiz-ready-", ".tmp");
            try { moveToReady(temp, ready, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) {
                Files.delete(ready); // 이 호출에서 만든 준비 파일만 제거한다.
                moveToReady(temp, ready);
            }
            try { link.publish(ready, target); }
            catch (FileAlreadyExistsException e) { throw e; }
            catch (UnsupportedOperationException e) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    throw new FileAlreadyExistsException(target.toString());
                move.publish(ready, target);
            }
            catch (IOException e) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    throw new FileAlreadyExistsException(target.toString());
                if (!unsupported(e)) throw e;
                move.publish(ready, target); // REPLACE_EXISTING을 지정하지 않는다.
            }
            return target;
        } finally {
            cleanup(temp);
            if (ready != null) cleanup(ready);
        }
    }

    /** 이 호출이 만든 임시→준비 파일 이동만 재시도한다. 최종 이름 게시/충돌에는 적용하지 않는다. */
    private void moveToReady(Path source, Path ready, CopyOption... options) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try { preparationMover.move(source, ready, options); return; }
            catch (AccessDeniedException denied) {
                if (attempt + 1 == retryPolicy.maxAttempts()) {
                    AccessDeniedException failure = new AccessDeniedException(source.toString(), ready.toString(),
                            "준비 파일 이동이 " + retryPolicy.maxAttempts() + "회 시도 후 실패했습니다. " + FILE_IN_USE_HINT);
                    // GUI는 cause의 끝을 표시한다. 안내를 유지하고 원래 오류/스택은 suppressed로 보존한다.
                    failure.addSuppressed(denied);
                    throw failure;
                }
                long delay = retryPolicy.delayMillis(attempt);
                logRetry("[SafeFileWriter] 준비 파일 이동 재시도 " + (attempt + 2) + "/" + retryPolicy.maxAttempts()
                        + ", 대기 " + delay + "ms (AccessDeniedException)");
                try { Thread.sleep(delay); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException error = new InterruptedIOException("준비 파일 이동 재시도가 중단됐습니다.");
                    error.initCause(interrupted); error.addSuppressed(denied); throw error;
                }
            }
        }
    }

    private void logRetry(String message) {
        try { retryLog.accept(message); }
        catch (RuntimeException ignored) { /* 진단 기록 실패가 파일 저장 정책을 바꾸지 않는다. */ }
    }

    private void cleanup(Path file) {
        try { cleaner.delete(file); }
        catch (IOException | RuntimeException ignored) { /* 성공한 게시를 정리 오류로 뒤집지 않는다. */ }
    }

    private static boolean unsupported(IOException error) {
        if (error instanceof AccessDeniedException || error instanceof NoSuchFileException) return false;
        String reason = error instanceof FileSystemException fs ? fs.getReason() : error.getMessage();
        if (reason == null) return false;
        String text = reason.toLowerCase(Locale.ROOT);
        return text.contains("not supported") || text.contains("unsupported") || text.contains("incorrect function")
                || text.contains("not implemented") || text.contains("지원하지") || text.contains("지원되지")
                || text.contains("잘못된 기능") || text.contains("함수가 올바르지");
    }
}
