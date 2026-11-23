package com.quiz;

import java.nio.file.*;
import java.io.IOException;

public final class LegacyFixtures {
    public static final String SET_ID = "11111111-1111-4111-8111-111111111111";
    public static final String RESULT_ID = "55555555-5555-4555-8555-555555555555";
    private LegacyFixtures() { }

    public static Path copy(String resource, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (var input = LegacyFixtures.class.getResourceAsStream("/legacy/" + resource)) {
            if (input == null) throw new IOException("테스트 리소스 없음: " + resource);
            Files.copy(input, file);
        }
        return file;
    }
}
