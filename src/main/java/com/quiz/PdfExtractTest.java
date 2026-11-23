package com.quiz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class PdfExtractTest {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 1 ? args[1] : "extracted.txt");
        List<String> pages = new PdfReader().readPages("sample.pdf");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            int page = i + 1;
            String text = pages.get(i);
            sb.append("===== PAGE ").append(page).append(" =====\n");
            sb.append(text).append("\n");
            long characters = text.codePoints().filter(c -> !Character.isWhitespace(c)).count();
            System.out.println("page=" + page + ", nonWhitespaceCharacters=" + characters);
        }

        Files.writeString(output, sb.toString());
        System.out.println("페이지 수: " + pages.size());
        System.out.println(output + " 저장 완료");
    }
}