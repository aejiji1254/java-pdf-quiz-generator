package com.quiz;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** 명시 실행: mvn test -Dtest=NotebookDataGuard -Dquiz.guard=true. 자식 mvn test 전후 실제 data/를 읽기만 한다. */
public class NotebookDataGuard {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void normalMavenTestLeavesRealProjectDataUnchanged() throws Exception {
        assumeTrue(Boolean.getBoolean("quiz.guard"));
        Path data = Path.of("data").toAbsolutePath();
        var before = NotebookFixtures.snapshot(data);
        Path log = temporary.getRoot().toPath().resolve("maven.log");
        String binary = System.getProperty("os.name").startsWith("Windows") ? "mvn.cmd" : "mvn";
        ProcessBuilder command = System.getProperty("os.name").startsWith("Windows")
                ? new ProcessBuilder("cmd.exe", "/d", "/c", binary, "test") : new ProcessBuilder(binary, "test");
        Process process = command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); process.waitFor(); }
        assertEquals("실제 data/ 파일 목록·바이트·수정 시각 불변", before, NotebookFixtures.snapshot(data));
        System.out.println("NOTEBOOK_REAL_DATA_GUARD entries=" + before.size() + " unchanged=true childExit=" + (finished ? process.exitValue() : "timeout"));
        assertTrue("자식 mvn test 시간 초과", finished);
        assertEquals("자식 mvn test 실패: " + new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8), 0, process.exitValue());
    }
}
